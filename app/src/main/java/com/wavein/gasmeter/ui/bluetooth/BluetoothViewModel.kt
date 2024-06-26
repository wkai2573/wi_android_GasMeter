package com.wavein.gasmeter.ui.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.snackbar.Snackbar
import com.wavein.gasmeter.tools.Preference
import com.wavein.gasmeter.tools.SharedEvent
import com.wavein.gasmeter.tools.rd64h.*
import com.wavein.gasmeter.tools.rd64h.info.*
import com.wavein.gasmeter.ui.loading.Tip
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject


@HiltViewModel
@SuppressLint("MissingPermission")
@OptIn(ExperimentalUnsignedTypes::class)
class BluetoothViewModel @Inject constructor(
	private val savedStateHandle:SavedStateHandle, //導航參數(hilt注入)
	// 注入實例
	private val bluetoothAdapter:BluetoothAdapter?,
) : ViewModel() {

	init {
		if (bluetoothAdapter == null) {
			viewModelScope.launch {
				SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("此裝置不支援藍牙", SharedEvent.Color.Error))
			}
		}
	}

	//region 開啟藍牙相關__________________________________________________

	// is藍牙開啟
	fun isBluetoothOn():Boolean {
		return bluetoothAdapter?.isEnabled ?: false
	}

	// 藍牙未開啟提示
	private suspend fun bluetoothOffTip():Boolean {
		if (!isBluetoothOn()) {
			SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("請開啟藍牙", SharedEvent.Color.Error, Snackbar.LENGTH_INDEFINITE))
			return true
		}
		return false
	}

	// 檢查藍牙並請求開啟
	fun checkBluetoothOn(bluetoothRequestLauncher:ActivityResultLauncher<Intent>) = viewModelScope.launch {
		SharedEvent.catching {
			if (bluetoothAdapter == null) {
				SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("此裝置不支援藍牙", SharedEvent.Color.Error, Snackbar.LENGTH_INDEFINITE))
				return@catching
			}
			if (!bluetoothAdapter.isEnabled) {
				requestBluetooth(bluetoothRequestLauncher)
			}
		}
	}

	// 請求開啟藍牙
	private fun requestBluetooth(bluetoothRequestLauncher:ActivityResultLauncher<Intent>) {
		val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
		bluetoothRequestLauncher.launch(enableBtIntent)
	}

	//endregion

	//region 藍牙相關(經典藍牙)__________________________________________________

	// 常數
	val pairableDeviceNames = listOf("RD64HGL", "MBH7BTZ43PANA")

	// 實例
	private var parentDeviceClient:ParentDeviceClient? = null

	// 可觀察變數
	val scanStateFlow = MutableStateFlow(ScanState.Idle)
	val scannedDeviceListStateFlow = MutableStateFlow(emptyList<BluetoothDevice>())
	var autoConnectDeviceStateFlow = MutableStateFlow<BluetoothDevice?>(null)

	// 取得已配對的RD-64H藍牙設備
	fun getBondedRD64HDevices():List<BluetoothDevice> {
		val devices:Set<BluetoothDevice> = bluetoothAdapter?.bondedDevices ?: emptySet()
		return devices.filter { it.name in pairableDeviceNames }
	}

	// 開始掃描
	fun toggleDiscovery() = viewModelScope.launch {
		SharedEvent.catching {
			if (!isBluetoothOn()) {
				SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("請開啟藍牙", SharedEvent.Color.Error))
				return@catching
			}
			if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return@catching
			if (bluetoothAdapter.isDiscovering) {
				stopDiscovery()
			} else {
				scanStateFlow.value = if (bluetoothAdapter.startDiscovery()) ScanState.Scanning else ScanState.Error
			}
		}
	}

	// 停止掃描
	fun stopDiscovery() {
		bluetoothAdapter?.cancelDiscovery()
	}

	// 選定為自動連結的設備
	private fun setAutoConnectBluetoothDevice(device:BluetoothDevice) {
		autoConnectDeviceStateFlow.value = device
	}

	// 連接藍牙設備
	fun connectDevice(device:BluetoothDevice? = autoConnectDeviceStateFlow.value) = viewModelScope.launch {
		SharedEvent.catching {
			if (bluetoothOffTip()) return@catching
			if (device == null) return@catching
			Preference[Preference.LAST_BT_DEVICE_MAC] = device.address
			setAutoConnectBluetoothDevice(device)
			parentDeviceClient = ParentDeviceClient(device)
			parentDeviceClient?.start()
		}
	}

	// 中斷連線
	fun disconnectDevice() {
		parentDeviceClient?.stopSocket()
		parentDeviceClient = null
		transceiver = null
		commStateFlow.value = CommState.NotConnected
		commTextStateFlow.value = Tip("未連結設備")
	}

	//endregion

	//region 藍牙資料收發__________________________________________________

	// 常數
	private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

	// 實例
	var transceiver:ParentDeviceTransceiver? = null

	// 可觀察事件
	val connectEventFlow = MutableSharedFlow<ConnectEvent>()

	// 藍牙連線事件
	fun onConnectEvent(event:ConnectEvent) = viewModelScope.launch {
		SharedEvent.catching {
			when (event) {
				ConnectEvent.Connecting -> {
					connectEventFlow.emit(ConnectEvent.Connecting)
					commStateFlow.value = CommState.Connecting
					commTextStateFlow.value = Tip("設備連結中")
				}

				ConnectEvent.ConnectionFailed -> {
					connectEventFlow.emit(ConnectEvent.ConnectionFailed)
					disconnectDevice()
				}

				ConnectEvent.ConnectionLost -> {
					// 如果是通信中途發生中斷, 要處理結果
					if (commStateFlow.value == CommState.Communicating) onCommEnd()
					connectEventFlow.emit(ConnectEvent.ConnectionLost)
					disconnectDevice()
				}

				ConnectEvent.Connected -> {
					connectEventFlow.emit(ConnectEvent.Connected)
					commStateFlow.value = CommState.ReadyCommunicate
					commTextStateFlow.value = Tip("設備已連結")
				}

				ConnectEvent.Listening -> {
					connectEventFlow.emit(ConnectEvent.Listening)
				}

				is ConnectEvent.BytesSent -> connectEventFlow.emit(event)
				is ConnectEvent.BytesReceived -> {
					connectEventFlow.emit(event)
					val readSP = event.byteArray
					onReceiveByStep(readSP)
				}

				else -> {}
			}
		}
	}

	// 母機連接
	private inner class ParentDeviceClient(private val device:BluetoothDevice) : Thread() {
		private var socket:BluetoothSocket? = null

		init {
			try {
				socket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID)
			} catch (e:IOException) {
				e.printStackTrace()
			}
		}

		override fun run() {
			try {
				onConnectEvent(ConnectEvent.Connecting)
				socket!!.connect()
				onConnectEvent(ConnectEvent.Connected)
				transceiver = ParentDeviceTransceiver(socket)
				transceiver!!.start()
			} catch (e:IOException) {
				onConnectEvent(ConnectEvent.ConnectionFailed)
				e.printStackTrace()
			}
		}

		fun stopSocket() {
			socket?.close()
		}
	}

	// 母機收發器
	inner class ParentDeviceTransceiver(private val bluetoothSocket:BluetoothSocket?) : Thread() {
		private val inputStream:InputStream?
		private val outputStream:OutputStream?

		init {
			var tempIn:InputStream? = null
			var tempOut:OutputStream? = null
			try {
				tempIn = bluetoothSocket!!.inputStream
				tempOut = bluetoothSocket.outputStream
			} catch (e:IOException) {
				e.printStackTrace()
			}
			inputStream = tempIn
			outputStream = tempOut
		}

		override fun run() {
			while (true) {
				onConnectEvent(ConnectEvent.Listening)
				val singleBuffer = ByteArray(320)
				try {
					val length = inputStream!!.read(singleBuffer)
					if (length != -1) {
						val bytes = singleBuffer.copyOfRange(0, length)
						onConnectEvent(ConnectEvent.BytesReceived(bytes))
					}
				} catch (e:IOException) {
					onConnectEvent(ConnectEvent.ConnectionLost)
					e.printStackTrace()
					break
				}
			}
		}

		fun write(bytes:ByteArray) {
			try {
				outputStream!!.write(bytes)
				onConnectEvent(ConnectEvent.BytesSent(bytes))
				Log.i("@@@ Send_Tx", RD64H.telegramConvert(bytes, "-p").toText())
			} catch (e:IOException) {
				e.printStackTrace()
			}
		}
	}
	//endregion

	//region 傳送接收訊息邏輯&UI層處理__________________________________________________

	private var startTime:Long = 0L // 計算耗時用

	// 發送Text給母機
	fun sendTextToDevice(toSendText:String) {
		if (toSendText.isEmpty()) return
		if (transceiver == null) return
		val sendSP = RD64H.telegramConvert(toSendText, "+s+p")
		transceiver?.write(sendSP)
	}

	// 相關屬性
	val commStateFlow = MutableStateFlow<CommState>(CommState.NotConnected)
	val commTextStateFlow = MutableStateFlow(Tip("未連結設備"))
	val commEndSharedEvent = MutableSharedFlow<CommEndEvent>()
	var commResult:MutableMap<String, BaseInfo> = mutableMapOf()

	// 溝通中處理變數
	private var callingChannel = "66" // 通信頻道, 通信前更改
	private var sendSteps = mutableListOf<BaseStep>()
	private var receiveSteps = mutableListOf<BaseStep>()
	private var totalReceiveCount = 0
	private var receivedCount = 0
	private var fullResp:UByteArray = UByteArray(0)
	private var timeoutJob:Job? = null

	// 溝通結束處理
	private fun onCommEnd() = viewModelScope.launch {
		SharedEvent.catching {
			if (commStateFlow.value != CommState.Communicating) return@catching
			// Log.i("@@@耗時", "${elapsedTime()} 秒 (總耗時)")
			// 恢復變數
			sendSteps.clear()
			receiveSteps.clear()
			timeoutJob?.cancel()
			// 異常1_自訂errMsg
			val err1 = if (commResult.containsKey("error_msg")) {
				val msg = (commResult["error_msg"] as BaseInfo).text
				msg
			} else ""
			// 異常2_指定回傳條件
			val err2 = if (commResult.containsKey("error_DL9")) {
				"通信忙線中，請稍後再試"
			} else if (commResult.containsKey("error_D16")) {
				"HHD用GW終端無回應(D16)"
			} else if (commResult.containsKey("error_D36")) {
				"對表U-bus通信異常(D36)"
			} else {
				""
			}
			// 依組合決定通信結束後小吃文字
			val metaInfo = commResult["meta"] as MetaInfo
			when (metaInfo.op) {
				// R80 異常訊息
				"R80" -> {
					val d05mList = if (commResult.containsKey("D05m")) {
						(commResult["D05m"] as D05mInfo).list
					} else {
						emptyList()
					}
					val notReadNumber = metaInfo.meterIds.size - d05mList.size
					if (notReadNumber > 0) {
						val errListText = "${d05mList.size}台抄表成功，${notReadNumber}台無回應\n請檢查未抄表瓦斯表"
						commResult["error"] = BaseInfo(listOf(err1, err2, errListText).filter { it.isNotEmpty() }.joinToString("\n"))
					} else {
						commResult["success"] = BaseInfo("${d05mList.size}台抄表成功")
					}
				}

				// R87 異常訊息
				// 檢查開始時R87傳了什麼steps，如果結果沒有對應的結果op，顯示對應錯誤
				"R87" -> {
					val errList = mutableListOf<String>()
					metaInfo.r87Steps!!.forEach { step ->
						when (step.op) {
							"R19" -> if (!commResult.containsKey("D87D19")) errList.add("時刻要求")
							"R23" -> if (!commResult.containsKey("D87D23") || (commResult["D87D23"] as D87D23Info).data.length != 65) errList.add("五回遮斷履歷")
							"R24" -> if (!commResult.containsKey("D87D24")) errList.add("告警情報or母火流量")
							"R16" -> if (!commResult.containsKey("D87D16")) errList.add("表狀態")
							"S16" -> if (!commResult.containsKey("D87D16")) errList.add("表狀態設定")
							"R57" -> if (!commResult.containsKey("D87D57")) errList.add("時間使用量")
							"R58" -> if (!commResult.containsKey("D87D58")) errList.add("最大使用量")
							"R59" -> if (!commResult.containsKey("D87D59")) errList.add("1日最大使用量")
							"S31" -> if (!commResult.containsKey("D87D31")) errList.add("母火流量設定")
							"R50" -> if (!commResult.containsKey("D87D50")) errList.add("壓力遮斷判定值")
							"S50" -> if (!commResult.containsKey("D87D50")) errList.add("壓力遮斷判定值設定")
							"R51" -> if (!commResult.containsKey("D87D51")) errList.add("現在壓力值")
							"C41" -> if (!commResult.containsKey("D87D41")) errList.add("中心遮斷")
							"C42" -> if (!commResult.containsKey("D87D42")) errList.add("中心遮斷解除")
							"C02" -> if (!commResult.containsKey("D87D02")) errList.add("強制Session中斷")
						}
					}
					if (errList.isNotEmpty() || err1.isNotEmpty() || err2.isNotEmpty()) {
						val errListText = "以下通信失敗：\n${errList.joinToString("\n")}"
						commResult["error"] = BaseInfo(listOf(err1, err2, errListText).filter { it.isNotEmpty() }.joinToString("\n"))
					} else {
						commResult["success"] = BaseInfo("讀取/設定成功")
					}
				}
			}
			// 結果處理
			if (commResult.containsKey("error")) {
				commEndSharedEvent.emit(CommEndEvent.Error(commResult))
			} else {
				commEndSharedEvent.emit(CommEndEvent.Success(commResult))
			}
			commStateFlow.value = CommState.ReadyCommunicate
			commTextStateFlow.value = Tip("通信完畢")
		}
	}

	// 發送自訂電文組合
	fun sendSingleTelegram(toSendText:String) = viewModelScope.launch {
		SharedEvent.catching {
			if (commStateFlow.value != CommState.ReadyCommunicate) return@catching
			commStateFlow.value = CommState.Communicating
			commResult = mutableMapOf()

			sendSteps = mutableListOf(
				RTestStep(toSendText), __AStep()
			)
			receiveSteps = mutableListOf(
				DTestStep()
			)
			totalReceiveCount = 1
			receivedCount = 0
			sendByStep()
		}
	}

	// 發送R80電文組合
	fun sendR80Telegram(meterIds:List<String>, callingChannel:String) = viewModelScope.launch {
		SharedEvent.catching {
			if (commStateFlow.value != CommState.ReadyCommunicate) return@catching
			this@BluetoothViewModel.callingChannel = callingChannel
			startTime = System.currentTimeMillis()
			commStateFlow.value = CommState.Communicating
			commResult = mutableMapOf("meta" to MetaInfo("", "R80", meterIds))

			sendSteps = mutableListOf(
				__5Step(),
				R80Step(meterIds),
				__AStep(),
			)
			receiveSteps = mutableListOf(
				D70Step(),
				D05Step(meterIds.size),
			)
			totalReceiveCount = 1 + meterIds.size
			receivedCount = 0
			sendByStep()
		}
	}

	// 發送R87電文組合
	fun sendR87Telegram(meterId:String, r87Steps:List<R87Step>, callingChannel:String) = viewModelScope.launch {
		SharedEvent.catching {
			if (commStateFlow.value != CommState.ReadyCommunicate) return@catching
			this@BluetoothViewModel.callingChannel = callingChannel
			startTime = System.currentTimeMillis()
			commStateFlow.value = CommState.Communicating
			commResult = mutableMapOf("meta" to MetaInfo("", "R87", listOf(meterId), r87Steps))

			sendSteps = mutableListOf(
				__5Step(),
				R89Step(meterId),
				//如果需分割, 要在下方補n個 R70
				*r87Steps.flatMapIndexed { index, step ->
					val cc2 = if (index == 0) "\u0040" else "\u0000"
					val cc3 = (appTelegramInc).toChar()
					appTelegramInc += 2
					when (step.op) {
						// 2part
						"R23" -> listOf(step.copy(cc = "\u0021$cc2$cc3\u0000"), R70Step(step.adr))
						// 1part
						else -> listOf(step.copy(cc = "\u0021$cc2$cc3\u0000"))
					}
				}.toTypedArray(),
				__AStep(),
			)
			receiveSteps = mutableListOf(
				D70Step(), D36Step(), *r87Steps.flatMapIndexed { index, step ->
					when (step.op) {
						"R01" -> listOf(D87D01Step())
						"R05" -> listOf(D87D05Step())
						"R19" -> listOf(D87D19Step())
						"R23" -> listOf(D87D23Step(), D87D23Step()) //R23有2part
						"R24" -> listOf(D87D24Step())
						"R16" -> listOf(D87D16Step())
						"S16" -> listOf(D87D16Step())
						"R57" -> listOf(D87D57Step())
						"R58" -> listOf(D87D58Step())
						"R59" -> listOf(D87D59Step())
						"R31" -> listOf(D87D31Step())
						"S31" -> listOf(D87D31Step())
						"R50" -> listOf(D87D50Step())
						"S50" -> listOf(D87D50Step())
						"R51" -> listOf(D87D51Step())
						"C41" -> listOf(D87D41Step())
						"C42" -> listOf(D87D42Step())
						"C02" -> listOf(D87D02Step())
						// todo 新增R87時_這裡加接收Step
						else -> listOf()
					}
				}.toTypedArray()
			)
			totalReceiveCount = receiveSteps.size
			receivedCount = 0
			sendByStep()
		}
	}

	// 設定子機頻道
	fun sendTelegramToGW(meterId:String) = viewModelScope.launch {
		SharedEvent.catching {
			if (commStateFlow.value != CommState.ReadyCommunicate) return@catching
			startTime = System.currentTimeMillis()
			commStateFlow.value = CommState.Communicating
			commResult = mutableMapOf("meta" to MetaInfo("", "R89_GW", listOf(meterId)))

			sendSteps = mutableListOf(
				__5Step(),
				RTestStep("ZA${meterId}R8911ZD${meterId}R36"),
				RTestStep("ZD${meterId}R34"),
				RTestStep("ZD${meterId}S34@@@@O1030"),
				__AStep(),
			)
			receiveSteps = mutableListOf(
				D70Step(),
				D36Step(),
				DTestStep(),
				DTestStep(),
			)
			totalReceiveCount = receiveSteps.size
			receivedCount = 0
			sendByStep()
		}
	}

	// 依步驟發送電文 !!!電文處理中途
	private suspend fun sendByStep() = viewModelScope.launch {
		SharedEvent.catching {
			// Log.i("@@@耗時", "${elapsedTime()} 秒")
			withContext(Dispatchers.IO) { timeout() }
			if (sendSteps.isEmpty()) {
				onCommEnd()
				return@catching
			}
			when (val sendStep = sendSteps.removeAt(0)) {
				is RTestStep -> {
					val sendText = sendStep.text
					val sendSP = RD64H.telegramConvert(sendText, "+s+p")
					commTextStateFlow.value = Tip("通信中: $sendText [${sendSP.toHex()}]")
					wt(WT2)
					transceiver?.write(sendSP)
				}

				is __5Step -> {
					commTextStateFlow.value = Tip("正在與母機建立連結", "", progressText) // 5↔D70
					val sendSP = RD64H.telegramConvert("5", "+s+p")
					wt(WT134)
					transceiver?.write(sendSP)
				}

				is R80Step -> {
					commTextStateFlow.value = Tip("抄表中", "", progressText) // R80↔D05
					val btParentId = (commResult["D70"] as D70Info).btParentId
					val sendText = RD64H.createR80Text(btParentId, sendStep.meterIds, callingChannel)
					val sendSP = RD64H.telegramConvert(sendText, "+s+p")
					wt(WT134)
					transceiver?.write(sendSP)
				}

				is __AStep -> {
					val sendSP = RD64H.telegramConvert("A", "+s+p")
					wt(WT2)
					transceiver?.write(sendSP)
					delay(1000L)
					onCommEnd()
				}

				is R89Step -> {
					commTextStateFlow.value = Tip("正在要求通信許可", "", progressText) // R89↔D36
					val sendText = "ZA${sendStep.meterId}R89${callingChannel}ZD${sendStep.meterId}R36"
					val sendSP = RD64H.telegramConvert(sendText, "+s+p")
					wt(WT134)
					transceiver?.write(sendSP)
				}

				is R87Step -> {
					when (sendStep.op) {
						"R01" -> commTextStateFlow.value = Tip("正在讀取讀數", "", progressText) // R87R01
						"R05" -> commTextStateFlow.value = Tip("正在讀取讀數", "", progressText) // R87R05
						"R19" -> commTextStateFlow.value = Tip("正在讀取時刻", "", progressText) // R87R19
						"R23" -> commTextStateFlow.value = Tip("正在讀取五回遮斷履歷", "", progressText) // R87R23
						"R24" -> commTextStateFlow.value = Tip("正在讀取表內部資料", "", progressText) // R87R24
						"R16" -> commTextStateFlow.value = Tip("正在讀取表狀態", "", progressText) // R87R16
						"S16" -> commTextStateFlow.value = Tip("正在設定表狀態", "", progressText) // R87S16
						"R57" -> commTextStateFlow.value = Tip("正在讀取時間使用量", "", progressText) // R87R57
						"R58" -> commTextStateFlow.value = Tip("正在讀取最大使用量", "", progressText) // R87R58
						"R59" -> commTextStateFlow.value = Tip("正在讀取1日最大使用量", "", progressText) // R87R59
						"S31" -> commTextStateFlow.value = Tip("正在設定登錄母火流量", "", progressText) // R87S31
						"R50" -> commTextStateFlow.value = Tip("正在讀取壓力遮斷判定值", "", progressText) // R87R50
						"S50" -> commTextStateFlow.value = Tip("正在設定壓力遮斷判定值", "", progressText) // R87S50
						"R51" -> commTextStateFlow.value = Tip("正在讀取壓力值", "", progressText) // R87R51
						"C41" -> commTextStateFlow.value = Tip("正在設定中心遮斷", "", progressText) // R87C41
						"C02" -> commTextStateFlow.value = Tip("正在中斷Session", "", progressText) // R87C02
					}
					val r87 = "ZD${sendStep.adr}R87"
					val aLine = RD64H.createR87Aline(
						securityLevel = sendStep.securityLevel, cc = sendStep.cc, adr = sendStep.adr, op = sendStep.op, data = sendStep.data
					)
					val sendText = r87 + aLine.toText()
					val sendSP = RD64H.telegramConvert(sendText, "+s+p")
					wt(WT2)
					transceiver?.write(sendSP)
				}

				is R70Step -> {
					commTextStateFlow.value = commTextStateFlow.value.copy(subtitle = "", progress = progressText)
					val sendText = "ZD${sendStep.meterId}R70"
					val sendSP = RD64H.telegramConvert(sendText, "+s+p")
					wt(WT2)
					transceiver?.write(sendSP)
				}

				else -> {
					throw Exception("例外的OP: $sendStep")
				}
			}
		}
	}

	// timeout fun: 80秒無回應則結束通信
	private suspend fun timeout() {
		timeoutJob?.cancel()
		timeoutJob = viewModelScope.launch {
			delay(80000)
			commResult["error_msg"] = BaseInfo("通信逾時")
			onCommEnd()
		}
	}

	private val WT134 = 1000L // 1000L
	private val WT2 = 3500L // 3500L, 受信可能期間=3~6秒

	private suspend inline fun wt(delay:Long) {
		delay(delay)
	}

	// 經過時間
	private fun elapsedTime():String {
		val diffMillisecond = System.currentTimeMillis() - startTime
		val diffSecond = diffMillisecond.toFloat() / 1000
		return String.format("%.1f", diffSecond)
	}

	// 依步驟接收電文 !!!電文處理中途
	private fun onReceiveByStep(readSP:ByteArray) = viewModelScope.launch {
		SharedEvent.catching {
			withContext(Dispatchers.IO) { timeout() }
			if (receiveSteps.isEmpty()) {
				onCommEnd()
				return@catching
			}

			// 檢查是否完整電文(前後有STX,ETX,BCC), 若不完整則等下個電文再串接
			val readS = RD64H.telegramConvert(readSP, "-p").toUByteArray()
			Log.i("@@@ Recv_Tx", """TEXT:[${readS.toText()}] HEX:[${readSP.toHex()}]""")
			if (readS[0] == RD64H.STXByte) {
				fullResp = readS.copyOf()
			} else {
				fullResp += readS
			}
			if (RD64H.checkBCC(fullResp)) {
				fullResp = fullResp.copyOfRange(1, fullResp.size - 2)
			} else {
				return@catching
			}
			val fullRespText = fullResp.toText()

			// 確定接收完整電文，執行電文接收處理
			receivedCount++
			var continueSend = false
			val receiveStep = receiveSteps[0]

			try {
				when (receiveStep) {
					is DTestStep -> {
						commResult["single"] = BaseInfo(fullRespText)
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D70Step -> {
						val info = BaseInfo.get(fullRespText, D70Info::class.java) as D70Info
						commResult["D70"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D05Step -> {
						commTextStateFlow.value = commTextStateFlow.value.copy(progress = progressText)
						val info = BaseInfo.get(fullRespText, D05Info::class.java) as D05Info
						commResult["D05"] = info
						if (!commResult.containsKey("D05m")) commResult["D05m"] = D05mInfo()
						val d05InfoList = (commResult["D05m"] as D05mInfo).list
						d05InfoList.add(info)
						if (d05InfoList.size == receiveStep.count) {
							receiveSteps.removeAt(0)
							continueSend = true
						}
					}

					is D36Step -> {
						val info = BaseInfo.get(fullRespText, D36Info::class.java) as D36Info
						commResult["D36"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D01Step -> {
						val info = BaseInfo.get(fullRespText, D87D01Info::class.java) as D87D01Info
						commResult["D87D01"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D05Step -> {
						val info = BaseInfo.get(fullRespText, D87D05Info::class.java) as D87D05Info
						commResult["D87D05"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D19Step -> {
						val info = BaseInfo.get(fullRespText, D87D19Info::class.java) as D87D19Info
						commResult["D87D19"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D23Step -> {
						val info = (if (commResult.containsKey("D87D23")) commResult["D87D23"]
						else BaseInfo.get(fullRespText, D87D23Info::class.java)) as D87D23Info
						info.writePart(fullRespText)
						commResult["D87D23"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D24Step -> {
						val info = BaseInfo.get(fullRespText, D87D24Info::class.java) as D87D24Info
						commResult["D87D24"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D16Step -> {
						val info = BaseInfo.get(fullRespText, D87D16Info::class.java) as D87D16Info
						commResult["D87D16"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D57Step -> {
						val info = BaseInfo.get(fullRespText, D87D57Info::class.java) as D87D57Info
						commResult["D87D57"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D58Step -> {
						val info = BaseInfo.get(fullRespText, D87D58Info::class.java) as D87D58Info
						commResult["D87D58"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D59Step -> {
						val info = BaseInfo.get(fullRespText, D87D59Info::class.java) as D87D59Info
						commResult["D87D59"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D31Step -> {
						val info = BaseInfo.get(fullRespText, D87D31Info::class.java) as D87D31Info
						commResult["D87D31"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D50Step -> {
						val info = BaseInfo.get(fullRespText, D87D50Info::class.java) as D87D50Info
						commResult["D87D50"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D51Step -> {
						val info = BaseInfo.get(fullRespText, D87D51Info::class.java) as D87D51Info
						commResult["D87D51"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D41Step -> {
						val info = BaseInfo.get(fullRespText, D87D41Info::class.java) as D87D41Info
						commResult["D87D41"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D42Step -> {
						val info = BaseInfo.get(fullRespText, D87D42Info::class.java) as D87D42Info
						commResult["D87D42"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					is D87D02Step -> {
						val info = BaseInfo.get(fullRespText, D87D02Info::class.java) as D87D02Info
						commResult["D87D02"] = info
						receiveSteps.removeAt(0)
						continueSend = true
					}

					// todo 新增R87時_這裡加Step處理
				}

				if (continueSend) sendByStep()
			} catch (error:Exception) {
				error.printStackTrace()
				commResult["error_msg"] = BaseInfo(error.message ?: "")
				// 錯誤處理: 檢查是不是D16 或 D36
				kotlin.runCatching {
					val info = BaseInfo.get(fullRespText, D16Info::class.java) as D16Info
					commResult["error_D16"] = info
				}
				kotlin.runCatching {
					val info = BaseInfo.get(fullRespText, D36Info::class.java) as D36Info
					commResult["error_D36"] = info
				}
				kotlin.runCatching {
					val info = BaseInfo.get(fullRespText, D87DL9Info::class.java) as D87DL9Info
					commResult["error_DL9"] = info
				}
				onCommEnd()
			}
		}
	}

	// 進度文字
	private val progressText:String
		get() {
			val percentage = (receivedCount.toDouble() / totalReceiveCount * 100).toInt()
			return "$percentage%"
		}

	//endregion

	companion object {
		var appTelegramInc = 0 // 電文Index遞增用
	}

}

// 掃描狀態
enum class ScanState { Idle, Scanning, Error }

// 連接事件
sealed class ConnectEvent {
	object Listening : ConnectEvent()
	object Connecting : ConnectEvent()
	object ConnectionFailed : ConnectEvent()
	object Connected : ConnectEvent()
	object ConnectionLost : ConnectEvent()
	data class BytesSent(val byteArray:ByteArray) : ConnectEvent()
	data class BytesReceived(val byteArray:ByteArray) : ConnectEvent()
}

// 溝通狀態
sealed class CommState {
	object NotConnected : CommState()
	object Connecting : CommState()
	object ReadyCommunicate : CommState()
	object Communicating : CommState()
}

// 溝通結束事件
sealed class CommEndEvent {
	data class Success(val commResult:Map<String, Any>) : CommEndEvent()
	data class Error(val commResult:Map<String, Any>) : CommEndEvent()
}