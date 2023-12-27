package com.wavein.gasmeter.ui.setting

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.doyaaaaaken.kotlincsv.dsl.csvWriter
import com.wavein.gasmeter.tools.Preference
import com.wavein.gasmeter.tools.SharedEvent
import com.wavein.gasmeter.tools.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.InputStreamReader
import java.util.UUID
import javax.inject.Inject

private const val uuidFilename = ".wavein.gasmeter.uuid"

private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private val opMeaningMap = mapOf(
	"S16" to "表狀態",
	"S31" to "登錄母火流量",
	"S50" to "壓力遮斷判定值",
	"C41" to "中心遮斷",
)

@HiltViewModel
@SuppressLint("MissingPermission")
class SettingViewModel @Inject constructor(
	private val savedStateHandle:SavedStateHandle, //導航參數(hilt注入)
) : ViewModel() {

	val uuidStateFlow = MutableStateFlow("")
	val sessionKeyFlow = MutableStateFlow("")

	init {
		initUuid()
		initSessionKey()
	}

	// 初始化UUID
	fun initUuid() = viewModelScope.launch {
		val uuid = readDocumentFileContent() ?: createUuidFileSaveToExternalStorage()
		if (uuid == null) {
			SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("無法生成UUID", SharedEvent.Color.Error))
		} else {
			uuidStateFlow.value = uuid
		}
	}

	// 初始化SessionKey
	private fun initSessionKey() {
		sessionKeyFlow.value = Preference[Preference.SESSION_KEY, ""] ?: ""
	}

	// 讀取UUID
	private fun readDocumentFileContent():String? {
		try {
			val documentsDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), uuidFilename)

			return if (documentsDirectory.exists()) {
				val reader = BufferedReader(FileReader(documentsDirectory))
				val stringBuilder = StringBuilder()
				var line:String? = reader.readLine()
				while (line != null) {
					stringBuilder.append(line).append("\n")
					line = reader.readLine()
				}
				reader.close()

				val fileContent = stringBuilder.toString().trimEnd('\n')
				fileContent
			} else {
				null // File does not exist.
			}
		} catch (e:Exception) {
			e.printStackTrace()
			return null
		}
	}

	// 產生UUID檔案並儲存至文件資料夾
	private fun createUuidFileSaveToExternalStorage():String? {
		val directoryType = Environment.DIRECTORY_DOCUMENTS

		runCatching {
			val externalStorageState = Environment.getExternalStorageState()
			if (Environment.MEDIA_MOUNTED == externalStorageState) {
				val directory = Environment.getExternalStoragePublicDirectory(directoryType)
				directory.mkdirs() // 確保目錄存在

				val file = File(directory, uuidFilename)
				FileOutputStream(file).use { outputStream ->
					runCatching {
						val uuid = UUID.randomUUID().toString().substring(0, 8)
						outputStream.write(uuid.toByteArray())
						return uuid
					}.onFailure {
						it.printStackTrace()
					}
				}
			}
		}.onFailure {
			it.printStackTrace()
		}
		return null
	}

	/** 建立log檔案 (儲存到本機/documents/log/)
	 * 上傳log參考: FtpViewModel.uploadLog()
	 */
	fun createLogFile(meterId:String, op:String, oldValue:String = "", newValue:String = "") {
		// log內容
		val filename = "${meterId}_${TimeUtils.getCurrentTime("yyyyMMdd_HHmmss")}.log"
		val rows:List<List<String>> = listOf(
			listOf("通信ID(表ID)", "時間", "操作", "操作碼", "原值", "新值"),
			listOf(meterId, TimeUtils.getCurrentTime(), opMeaningMap[op] ?: "", op, oldValue, newValue),
		)
		val csvContent = csvWriter().writeAllAsString(rows)

		// 寫入本機/documents
		try {
			val externalStorageState = Environment.getExternalStorageState()
			if (Environment.MEDIA_MOUNTED == externalStorageState) {
				val documentsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
				documentsFolder.mkdirs()
				val logFolder = File(documentsFolder, "log")
				logFolder.mkdirs()
				val file = File(logFolder, filename)
				val outputStream = FileOutputStream(file)
				outputStream.write(bom + csvContent.toByteArray())
			}
		} catch (e:Exception) {
			e.printStackTrace()
		}
	}

}

