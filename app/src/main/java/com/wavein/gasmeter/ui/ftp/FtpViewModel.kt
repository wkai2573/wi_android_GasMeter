package com.wavein.gasmeter.ui.ftp

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.wavein.gasmeter.Application.Companion.IS_DEV_MODE
import com.wavein.gasmeter.tools.Preference
import com.wavein.gasmeter.tools.SharedEvent
import com.wavein.gasmeter.tools.TimeUtils
import com.wavein.gasmeter.ui.meterwork.MeterViewModel
import com.wavein.gasmeter.ui.setting.CsvViewModel
import com.wavein.gasmeter.ui.setting.FileState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class FtpViewModel @Inject constructor(
	private val savedStateHandle:SavedStateHandle, //導航參數(hilt注入)
) : ViewModel() {

	// 可觀察變數
	var systemAreaOpenedStateFlow = MutableStateFlow(false)
	val appStateFlow = MutableStateFlow(AppState.NotChecked)
	val ftpConnStateFlow = MutableStateFlow<FtpConnState>(FtpConnState.Idle)

	// 系統、下載、上傳ftp
	var systemFtpInfo:FtpInfo = FtpInfo(
		FtpEnum.System,
		Preference[Preference.FTP_SYSTEM_HOST, "118.163.191.31"]!!,
		Preference[Preference.FTP_SYSTEM_USERNAME, "aktwset01"]!!,
		Preference[Preference.FTP_SYSTEM_PASSWORD, "NSsetup09"]!!,
		Preference[Preference.FTP_SYSTEM_ROOT, "WaveIn/system"]!!
	)

	var downloadFtpInfo:FtpInfo = FtpInfo(
		FtpEnum.Download,
		Preference[Preference.FTP_DOWNLOAD_HOST, if (IS_DEV_MODE) "118.163.191.31" else ""]!!,
		Preference[Preference.FTP_DOWNLOAD_USERNAME, if (IS_DEV_MODE) "aktwset01" else ""]!!,
		Preference[Preference.FTP_DOWNLOAD_PASSWORD, if (IS_DEV_MODE) "NSsetup09" else ""]!!,
		Preference[Preference.FTP_DOWNLOAD_ROOT, if (IS_DEV_MODE) "WaveIn/download" else ""]!!
	)

	var uploadFtpInfo:FtpInfo = FtpInfo(
		FtpEnum.Upload,
		Preference[Preference.FTP_UPLOAD_HOST, if (IS_DEV_MODE) "118.163.191.31" else ""]!!,
		Preference[Preference.FTP_UPLOAD_USERNAME, if (IS_DEV_MODE) "aktwset01" else ""]!!,
		Preference[Preference.FTP_UPLOAD_PASSWORD, if (IS_DEV_MODE) "NSsetup09" else ""]!!,
		Preference[Preference.FTP_UPLOAD_ROOT, if (IS_DEV_MODE) "WaveIn/upload" else ""]!!
	)

	fun saveFtpInfo(ftpInfo:FtpInfo) {
		val mFtpInfo = when (ftpInfo.ftpEnum) {
			FtpEnum.System -> {
				Preference[Preference.FTP_SYSTEM_HOST] = ftpInfo.host
				Preference[Preference.FTP_SYSTEM_USERNAME] = ftpInfo.username
				Preference[Preference.FTP_SYSTEM_PASSWORD] = ftpInfo.password
				Preference[Preference.FTP_SYSTEM_ROOT] = ftpInfo.root
				systemFtpInfo
			}

			FtpEnum.Download -> {
				Preference[Preference.FTP_DOWNLOAD_HOST] = ftpInfo.host
				Preference[Preference.FTP_DOWNLOAD_USERNAME] = ftpInfo.username
				Preference[Preference.FTP_DOWNLOAD_PASSWORD] = ftpInfo.password
				Preference[Preference.FTP_DOWNLOAD_ROOT] = ftpInfo.root
				downloadFtpInfo
			}

			FtpEnum.Upload -> {
				Preference[Preference.FTP_UPLOAD_HOST] = ftpInfo.host
				Preference[Preference.FTP_UPLOAD_USERNAME] = ftpInfo.username
				Preference[Preference.FTP_UPLOAD_PASSWORD] = ftpInfo.password
				Preference[Preference.FTP_UPLOAD_ROOT] = ftpInfo.root
				uploadFtpInfo
			}
		}
		mFtpInfo.apply {
			host = ftpInfo.host
			username = ftpInfo.username
			password = ftpInfo.password
			root = ftpInfo.root
		}
	}

	// 編碼
	private val LOCAL_CHARSET = Charsets.UTF_8
	private val SERVER_CHARSET = Charsets.ISO_8859_1
	private fun encode(text:String):String = String(text.toByteArray(LOCAL_CHARSET), SERVER_CHARSET)
	private fun decode(text:String):String = String(text.toByteArray(SERVER_CHARSET), LOCAL_CHARSET)

	/**
	 * ftp連線到結束
	 * @param ftpInfo FTP連線資訊
	 * @param path 進入目錄
	 * @param autoDisconnect 自動中斷FTP，若連線中有延遲處理則需要設為false，並自行呼叫disconnect(ftpClient)
	 * @param ftpLoginError 當連結失敗時的處理，return true 顯示原因小吃(預設)
	 * @param ftpHandle 連線FTP中要做的事
	 */
	private fun ftpProcess(
		ftpInfo:FtpInfo,
		path:String = "",
		autoDisconnect:Boolean = true,
		ftpLoginError:() -> Boolean = { true },
		ftpHandle:(ftpClient:FTPClient) -> Unit = {}
	) {
		CoroutineScope(Dispatchers.IO).launch {
			val ftpClient = FTPClient()
			try {
				ftpConnStateFlow.value = FtpConnState.Connecting("正在連線FTP")
				ftpClient.connect(ftpInfo.host)
				ftpConnStateFlow.value = FtpConnState.Connecting("FTP登入中")
				if (!ftpClient.login(ftpInfo.username, ftpInfo.password)) {
					if (ftpLoginError()) showSnack("無法登入FTP")
					return@launch
				}
				ftpConnStateFlow.value = FtpConnState.Connected
				ftpClient.enterLocalPassiveMode()
				ftpClient.controlEncoding = "UTF-8" // 設置ftp控制編碼
				if (ftpInfo.root.isNotEmpty() && !ftpClient.changeWorkingDirectory(encode(ftpInfo.root))) {
					if (ftpLoginError()) showSnack("${ftpInfo.root} 目錄無法開啟")
					return@launch
				}
				if (path.isNotEmpty() && !ftpClient.makeAndChangeDirectory(encode(path))) {
					if (ftpLoginError()) showSnack("$path 目錄無法開啟")
					return@launch
				}
				ftpHandle(ftpClient)
			} catch (e:Exception) {
				if (ftpLoginError()) {
					if (e.message?.contains("Network is unreachable") == true) {
						showSnack("未連結網路")
					} else {
						showSnack("[FTP Error]\n${e.message}")
					}
				}
			} finally {
				if (autoDisconnect) disconnect(ftpClient)
			}
		}
	}

	private fun disconnect(ftpClient:FTPClient) {
		kotlin.runCatching { ftpClient.logout() }
		kotlin.runCatching { ftpClient.disconnect() }
		ftpConnStateFlow.value = FtpConnState.Idle
	}

	// ==Dialog==
	// 測試FTP
	fun testFtp(ftpInfo:FtpInfo) {
		ftpProcess(ftpInfo, "") { ftpClient ->
			showSnack("連線成功", SharedEvent.Color.Success)
		}
	}

	// ==SYSTEM==
	// 檢查產品開通
	fun checkAppActivate(uuid:String, appkey:String, company:String, dep:String, username:String) {
		appStateFlow.value = AppState.Checking
		if (uuid.isEmpty() || appkey.isEmpty()) {
			appStateFlow.value = AppState.Inactivated
			return
		}
		ftpProcess(systemFtpInfo, "key",
			ftpLoginError = {
				appStateFlow.value = AppState.Inactivated
				return@ftpProcess true
			},
			ftpHandle = { ftpClient ->
				if (!ftpClient.changeWorkingDirectory(encode(appkey))) {
					onAppkeyVerifyFail("序號錯誤")
					return@ftpProcess
				}
				val uuidFilename = "$uuid.txt"
				val files = ftpClient.listFiles()
				when {
					// 序號未被註冊，該序號綁定此裝置
					files.isEmpty() -> {
						ftpClient.setFileType(FTP.ASCII_FILE_TYPE)
						val uuidRows = listOf(
							listOf("公司", "部門", "使用者"),
							listOf(company, dep, username),
						)
						val uuidContent = csvWriter().writeAllAsString(uuidRows)
						val emptyInputStream = uuidContent.byteInputStream()
						if (ftpClient.storeFile(encode(uuidFilename), emptyInputStream)) {
							onAppkeyVerifySuccess("產品開通成功", appkey, company, dep, username)
						} else {
							onAppkeyVerifyFail("產品開通失敗 (無法建立uuid檔案於FTP)")
						}
					}
					// 檢查序號正確
					files[0].name == uuidFilename -> {
						Preference[Preference.APP_KEY] = appkey
						Preference[Preference.APP_ACTIVATED] = true
						Preference[Preference.USER_COMPANY] = company
						Preference[Preference.USER_DEP] = dep
						Preference[Preference.USER_NAME] = username
						appStateFlow.value = AppState.Activated
					}
					// 檢查序號錯誤，該序號已被其他裝置綁定
					else -> onAppkeyVerifyFail("此序號已被其他裝置註冊")
				}
			})
	}

	private fun onAppkeyVerifySuccess(msg:String, appkey:String, company:String, dep:String, username:String) {
		showSnack(msg, SharedEvent.Color.Success)
		Preference[Preference.APP_KEY] = appkey
		Preference[Preference.APP_ACTIVATED] = true
		Preference[Preference.USER_COMPANY] = company
		Preference[Preference.USER_DEP] = dep
		Preference[Preference.USER_NAME] = username
		appStateFlow.value = AppState.Activated
	}

	private fun onAppkeyVerifyFail(msg:String) {
		showSnack(msg)
		Preference[Preference.APP_KEY] = ""
		Preference[Preference.APP_ACTIVATED] = false
		Preference[Preference.USER_COMPANY] = ""
		Preference[Preference.USER_DEP] = ""
		Preference[Preference.USER_NAME] = ""
		appStateFlow.value = AppState.Inactivated
	}

	private fun showSnack(msg:String, snackbarColor:SharedEvent.Color = SharedEvent.Color.Error) {
		viewModelScope.launch {
			SharedEvent.eventFlow.emit(
				SharedEvent.ShowSnackbar(
					msg, snackbarColor,
					duration = if (snackbarColor == SharedEvent.Color.Error) Snackbar.LENGTH_INDEFINITE else Snackbar.LENGTH_SHORT,
				)
			)
		}
	}

	// ==UPLOAD==
	fun uploadFile(context:Context, fileState:FileState) {
		val path = Preference[Preference.APP_KEY, ""]!!
		if (path.isEmpty() || !fileState.isOpened) return

		ftpProcess(uploadFtpInfo, path) { ftpClient ->
			val filenameWithTime = "${fileState.nameWithoutExtension}_${TimeUtils.getCurrentTime("yyyyMMddHH")}.${fileState.extension}"
			ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
			context.contentResolver.openFileDescriptor(fileState.uri!!, "r").use { parcelFileDescriptor ->
				val fileDescriptor = parcelFileDescriptor?.fileDescriptor
				val inputStream = FileInputStream(fileDescriptor)
				ftpClient.storeFile(encode(filenameWithTime), inputStream)
				showSnack("上傳成功, FTP目錄: $path/$filenameWithTime", SharedEvent.Color.Success)
			}
		}
	}

	/** 上傳log: 將手機/Documents/log 裡的檔案上傳至ftp, 上傳成功後移動至 log_uploaded_yyyy, 移除3年前的 log_uploaded_yyyy
	 *  建立log參考: SettingViewModel.createLogFile()
	 */
	fun uploadLog() {
		val documentsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
		documentsFolder.mkdirs()
		val logFolder = File(documentsFolder, "log")
		logFolder.mkdirs()
		val logUploadedFolder = File(documentsFolder, "log_uploaded_${TimeUtils.getCurrentTime("yyyy")}")
		logUploadedFolder.mkdirs()
		val files = logFolder.listFiles() ?: return

		ftpProcess(systemFtpInfo, "log") { ftpClient ->
			files.forEach { file ->
				// 上傳ftp
				val inputStream = FileInputStream(file)
				val uploadSuccess = kotlin.runCatching { ftpClient.storeFile(encode(file.name), inputStream) }.getOrElse { false }
				// 移動檔案 (log > log_uploaded_yyyy)
				if (uploadSuccess) {
					val destinationFile = File(logUploadedFolder, file.name)
					file.renameTo(destinationFile)
				}
			}
		}

		// 移除3年前的 log_uploaded_yyyy
		val threeYearsAgo = LocalDate.now().minusYears(3).format(DateTimeFormatter.ofPattern("yyyy"))
		val log3yearsAgoFolder = File(documentsFolder, "log_uploaded_$threeYearsAgo")
		log3yearsAgoFolder.deleteRecursively()
	}

	// ==DOWNLOAD==
	// 根目錄
	private val rootDirectory get() = Environment.getExternalStorageDirectory()

	// Download資料夾
	private val downloadDirectory get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
	// 外部空間根目錄/NXU Gas Meter
	// private val directory get() = File(rootDirectory, "NXU Gas Meter")

	fun downloadFileOpenFolder(context:Context, csvVM:CsvViewModel, meterVM:MeterViewModel) {
		ftpProcess(downloadFtpInfo, "") { ftpClient ->
			ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
			val fileArray = ftpClient.listFiles()
				.map { ftpFile -> ftpFile.name }
				.filter { filename -> filename.endsWith(".csv", true) }
				.toTypedArray()
			if (fileArray.isEmpty()) {
				showSnack("沒有找到任何.csv檔案")
				return@ftpProcess
			}
			viewModelScope.launch {
				val builder = MaterialAlertDialogBuilder(context)
					.setTitle("選擇檔案")
					.setItems(fileArray) { dialogInterface, index ->
						dialogInterface.dismiss()
						val filename = fileArray[index]
						downloadFileCheckOverwrite(context, csvVM, meterVM, filename)
					}
					.create()
				SharedEvent.eventFlow.emit(SharedEvent.ShowDialogB(builder))
			}
		}
	}

	private fun downloadFileCheckOverwrite(context:Context, csvVM:CsvViewModel, meterVM:MeterViewModel, filename:String) {
		downloadDirectory.mkdirs()
		val localFile = File(downloadDirectory, filename)
		if (localFile.exists()) {
			viewModelScope.launch {
				val builder = MaterialAlertDialogBuilder(context)
					.setTitle("檔案已存在")
					.setMessage("是否覆蓋 \"$filename\"")
					.setNegativeButton("取消") { dialog, which -> dialog.dismiss() }
					.setPositiveButton("確定") { dialog, which ->
						dialog.dismiss()
						downloadFile(context, csvVM, meterVM, filename)
					}
					.create()
				SharedEvent.eventFlow.emit(SharedEvent.ShowDialogB(builder))
			}
		} else {
			downloadFile(context, csvVM, meterVM, filename)
		}
	}

	private fun downloadFile(context:Context, csvVM:CsvViewModel, meterVM:MeterViewModel, filename:String) {
		ftpProcess(downloadFtpInfo, "") { ftpClient ->
			downloadDirectory.mkdirs()
			val localFile = File(downloadDirectory, filename)
			val outputStream = FileOutputStream(localFile)
			val success = ftpClient.retrieveFile(encode(filename), outputStream)
			outputStream.close()
			if (success) {
				showSnack("\"$filename\" 已保存於 \"/${downloadDirectory.toRelativeString(rootDirectory)}\"", SharedEvent.Color.Success)
				csvVM.readCsv(context, Uri.fromFile(localFile), meterVM, filename)
			} else {
				showSnack("下載失敗")
			}
		}
	}

	//__________ 以下參考 __________

	// 上傳空文件
	fun uploadEmptyFile(ftpInfo:FtpInfo, path:String, filename:String) {
		ftpProcess(ftpInfo, path) { ftpClient ->
			ftpClient.setFileType(FTP.ASCII_FILE_TYPE)
			val emptyInputStream = "".byteInputStream()
			ftpClient.storeFile(encode(filename), emptyInputStream)
		}
	}

	// 上傳文件
	fun uploadFile_r(ftpInfo:FtpInfo, path:String, filename:String, fileDescriptor:FileDescriptor) {
		ftpProcess(ftpInfo, path) { ftpClient ->
			ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
			val inputStream = FileInputStream(fileDescriptor)
			ftpClient.storeFile(encode(filename), inputStream)
		}
	}

	// 刪除檔案
	fun deleteFile(ftpInfo:FtpInfo, path:String, filename:String) {
		ftpProcess(ftpInfo, path) { ftpClient ->
			ftpClient.deleteFile(filename)
		}
	}

	// 問chatGPT用
	fun uploadFileToFtp(filename:String, host:String, username:String, password:String, directory:String, fileDescriptor:FileDescriptor) {
		val ftpClient = FTPClient()
		ftpClient.connect(host)
		ftpClient.login(username, password)
		ftpClient.enterLocalPassiveMode()
		ftpClient.setFileType(FTP.ASCII_FILE_TYPE)
		ftpClient.changeWorkingDirectory(directory)

		ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
		val inputStream = FileInputStream(fileDescriptor)
		ftpClient.storeFile(filename, inputStream)
		inputStream.close()

		ftpClient.logout()
		ftpClient.disconnect()
	}

}

// 進入目錄，沒有則創建再進入 (進入根的時候不要用)
private fun FTPClient.makeAndChangeDirectory(directory:String):Boolean {
	val directories = directory.split("/")
	for (dir in directories) {
		if (dir.isEmpty()) continue
		if (!this.changeWorkingDirectory(dir)) {
			this.makeDirectory(dir)
			if (!this.changeWorkingDirectory(dir)) {
				return false
			}
		}
	}
	return true
}

// class
data class FtpInfo(val ftpEnum:FtpEnum, var host:String = "", var username:String = "", var password:String = "", var root:String = "")
enum class FtpEnum { System, Download, Upload }

enum class AppState { NotChecked, Checking, Activated, Inactivated }

sealed class FtpConnState {
	object Idle : FtpConnState()
	data class Connecting(val msg:String) : FtpConnState()
	object Connected : FtpConnState()
	// data class Error(val msg:String) : FtpConnState()
}