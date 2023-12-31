package com.wavein.gasmeter.ui.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavein.gasmeter.tools.SharedEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

// 低功耗藍牙VM (目前無使用)
@HiltViewModel
@SuppressLint("MissingPermission")
class BleViewModel @Inject constructor(
	private val savedStateHandle:SavedStateHandle, //導航參數(hilt注入)
	// 注入實例
	private val bluetoothManager:BluetoothManager?,
	private val bluetoothAdapter:BluetoothAdapter?,
	private val bluetoothLeScanner:BluetoothLeScanner?,
) : ViewModel() {

	init {
		if (bluetoothAdapter == null) {
			viewModelScope.launch {
				SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("設備不支援藍牙", SharedEvent.Color.Error))
			}
		}
	}

	// BLE相關(低功耗藍牙)__________________________________________________
	// https://developer.android.com/guide/topics/connectivity/bluetooth/find-ble-devices

	// 實例
	lateinit var leDeviceListAdapter:DeviceListAdapter

	// 常數
	private val scanFilters:List<ScanFilter> = listOf(
		ScanFilter.Builder()
			// .setDeviceName("Pokemon GO Plus +")
			.build()
	)
	private val scanSettings:ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
	private val scanPeriod:Long = 5000

	// 可觀察變數
	val scanStateFlow = MutableStateFlow(ScanState.Idle)
	val bleDeviceListStateFlow = MutableStateFlow(emptyList<BluetoothDevice>())

	// 實例初始化
	fun setDeviceOnClick(itemOnClick:(BluetoothDevice) -> Unit) {
		leDeviceListAdapter = DeviceListAdapter(itemOnClick)
	}

	// 開始掃描
	fun scanLeDevice() = viewModelScope.launch {
		if (scanStateFlow.value != ScanState.Scanning) {
			scanStateFlow.value = ScanState.Scanning
			bluetoothLeScanner?.startScan(scanFilters, scanSettings, leScanCallback)
			delay(scanPeriod)
			scanStateFlow.value = ScanState.Idle
			bluetoothLeScanner?.stopScan(leScanCallback)
		} else {
			scanStateFlow.value = ScanState.Idle
			bluetoothLeScanner?.stopScan(leScanCallback)
		}
	}

	// 掃描結果CB
	private val leScanCallback = object : ScanCallback() {
		override fun onScanResult(callbackType:Int, result:ScanResult) {
			super.onScanResult(callbackType, result)
			val btDeviceList = bleDeviceListStateFlow.value.toMutableList()
			if (!btDeviceList.any { it.address == result.device.address }) {
				btDeviceList.add(result.device)
				bleDeviceListStateFlow.value = btDeviceList.toList()
			}
		}
	}

	// 連接BLE設備__________________________________________________

	// 實例
	private var bluetoothGatt:BluetoothGatt? = null

	// 常數(服務&特性UUID)
	val serviceUuid = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455")!!
	val characteristicUuid = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616")!!

	// 可觀察變數
	val bluetoothProfileStateFlow = MutableStateFlow<Int>(-1)
	//val scanningStateFlow = MutableStateFlow(false)

	// 連接BLE設備
	fun connectBleDevice(context:Context, bleDevice:BluetoothDevice) {
		bluetoothGatt = bleDevice.connectGatt(context, true, gattCallback)
	}

	// 關閉BLE設備連接
	fun closeConnect() {
		bluetoothGatt?.close()
		bluetoothGatt = null
	}

	// 連接設備CB
	private val gattCallback = object : BluetoothGattCallback() {
		override fun onConnectionStateChange(gatt:BluetoothGatt, status:Int, newState:Int) {
			bluetoothProfileStateFlow.value = newState
			when (newState) {
				// 連接成功，開始發現服務
				BluetoothProfile.STATE_CONNECTED -> {
					gatt.discoverServices()
				}

				// 連接失敗
				BluetoothProfile.STATE_DISCONNECTED -> {
					bluetoothGatt = null
				}
			}
		}

		override fun onServicesDiscovered(gatt:BluetoothGatt, status:Int) {
			// 發現 BLE 服務後，找到目標特性並進行相應操作
			val service = gatt.getService(serviceUuid)
			val characteristic = service?.getCharacteristic(characteristicUuid)

			if (characteristic != null) {
				// 在這裡進行您的特性操作，例如讀取或寫入數據
				// gatt.readCharacteristic(targetCharacteristic)
				// gatt.writeCharacteristic(targetCharacteristic)
			}
		}
	}

}