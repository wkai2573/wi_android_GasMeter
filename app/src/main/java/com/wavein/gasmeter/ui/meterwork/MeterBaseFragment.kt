package com.wavein.gasmeter.ui.meterwork

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.wavein.gasmeter.R
import com.wavein.gasmeter.data.model.MeterRow
import com.wavein.gasmeter.databinding.FragmentMeterBaseBinding
import com.wavein.gasmeter.tools.SharedEvent
import com.wavein.gasmeter.tools.TimeUtil
import com.wavein.gasmeter.tools.rd64h.info.D05mInfo
import com.wavein.gasmeter.ui.NavViewModel
import com.wavein.gasmeter.ui.bluetooth.BluetoothViewModel
import com.wavein.gasmeter.ui.bluetooth.BtDialogFragment
import com.wavein.gasmeter.ui.bluetooth.CommEndEvent
import com.wavein.gasmeter.ui.bluetooth.CommState
import com.wavein.gasmeter.ui.bluetooth.ConnectEvent
import com.wavein.gasmeter.ui.loading.Tip
import com.wavein.gasmeter.ui.meterwork.groups.MeterGroupsFragment
import com.wavein.gasmeter.ui.meterwork.list.MeterListFragment
import com.wavein.gasmeter.ui.meterwork.row.MeterRowFragment
import com.wavein.gasmeter.ui.setting.CsvViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


class MeterBaseFragment : Fragment() {

	// binding & viewModel
	private var _binding:FragmentMeterBaseBinding? = null
	private val binding get() = _binding!!
	private val navVM by activityViewModels<NavViewModel>()
	private val blVM by activityViewModels<BluetoothViewModel>()
	private val meterVM by activityViewModels<MeterViewModel>()
	private val csvVM by activityViewModels<CsvViewModel>()

	override fun onDestroyView() {
		super.onDestroyView()
		_binding = null
	}

	override fun onCreateView(inflater:LayoutInflater, container:ViewGroup?, savedInstanceState:Bundle?):View {
		_binding = FragmentMeterBaseBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view:View, savedInstanceState:Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		// 未選csv提示
		binding.noCsvTipTv.visibility = if (meterVM.meterRowsStateFlow.value.isEmpty()) View.VISIBLE else View.GONE

		// pager
		val meterPageAdapter = MeterPageAdapter(this)
		binding.pager.adapter = meterPageAdapter
		binding.pager.isUserInputEnabled = false  // 禁用滑動切換tab

		// tab
		TabLayoutMediator(binding.tabLayout, binding.pager) { tab, position ->
			when (position) {
				0 -> tab.text = "群組列表"
				1 -> tab.text = "群組瓦斯表"
				2 -> tab.text = "瓦斯表"
				else -> tab.text = "UNKNOWN: $position"
			}
		}.attach()

		// 訂閱選擇的群組, 禁用tab
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				meterVM.selectedMeterGroupStateFlow.collectLatest { meterGroup ->
					if (meterGroup == null) changeTab(0)
					binding.tabLayout.getTabAt(1)?.let { tab ->
						setTabView(meterGroup, tab)
					}
				}
			}
		}

		// 訂閱選擇的表, 禁用tab
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				meterVM.selectedMeterRowFlow.asStateFlow().collectLatest { meterRow ->
					binding.tabLayout.getTabAt(2)?.let { tab ->
						setTabView(meterRow, tab)
					}
				}
			}
		}

		// 訂閱切換tab
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				navVM.meterBaseChangeTabStateFlow.asStateFlow().collectLatest { tabIndex ->
					if (tabIndex == -1) return@collectLatest
					delay(1)
					changeTab(tabIndex, false)
					navVM.meterBaseChangeTabStateFlow.value = -1
				}
			}
		}

		// 訂閱點擊back事件: 檢查目前抄表頁裡面的分頁,決定執行動作
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				navVM.meterBaseBackKeyClickSharedFlow.asSharedFlow().collectLatest { smoothScroll ->
					if (binding.pager.currentItem == 2 && navVM.meterRowPageBackDestinationIsSearch) {
						navVM.navigate(R.id.nav_meterSearchFragment)
					} else {
						val toTabIndex = binding.pager.currentItem - 1
						if (toTabIndex >= 0) {
							changeTab(toTabIndex, smoothScroll)
						} else {
							navVM.navigate(R.id.nav_settingFragment)
						}
					}
				}
			}
		}

		// 連線相關的訂閱
		initConnSubscription()
	}

	// tabView禁用與顯示
	private fun setTabView(it:Any?, tab:TabLayout.Tab) {
		if (it == null) {
			tab.view.isEnabled = false
			tab.customView = TextView(requireContext()).apply {
				gravity = Gravity.CENTER
				setTextColor(Color.LTGRAY)
				text = tab.text
			}
		} else {
			tab.view.isEnabled = true
			tab.customView = null
		}
	}

	// 切換tab (給子fragment呼叫)
	fun changeTab(tabIndex:Int, smoothScroll:Boolean = true) {
		binding.pager.setCurrentItem(tabIndex, smoothScroll)
	}

	//region__________連線方法__________

	// 連線相關的訂閱
	private fun initConnSubscription() {

		// 訂閱通信中 進度文字
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				blVM.commTextStateFlow.asStateFlow().collectLatest {
					Log.i("@@@通信狀態", it.title)
					SharedEvent.loadingFlow.value = when (it.title) {
						"未連結設備", "通信完畢" -> Tip()
						"設備已連結" -> Tip("設備已連結，準備通信")
						else -> it.copy()
					}
				}
			}
		}

		// 訂閱藍牙事件
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				blVM.connectEventFlow.asSharedFlow().collectLatest { event ->
					when (event) {
						ConnectEvent.Connecting -> {}
						ConnectEvent.Connected -> {}
						ConnectEvent.ConnectionFailed -> {
							SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar("設備連結失敗", SharedEvent.Color.Error, Snackbar.LENGTH_INDEFINITE))
						}

						ConnectEvent.Listening -> {}
						ConnectEvent.ConnectionLost -> {}

						is ConnectEvent.BytesSent -> {}
						is ConnectEvent.BytesReceived -> {}
						else -> {}
					}
				}
			}
		}

		// 訂閱溝通狀態
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				blVM.commStateFlow.asStateFlow().collectLatest { state ->
					when (state) {
						CommState.NotConnected -> {}
						CommState.Communicating, CommState.Connecting -> {}
						CommState.ReadyCommunicate -> {
							onConnected?.invoke()
							onConnected = null
						}

						else -> {}
					}
				}
			}
		}

		// 訂閱溝通結束事件
		viewLifecycleOwner.lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				blVM.commEndSharedEvent.asSharedFlow().collectLatest { event ->
					when (event) {
						is CommEndEvent.Success -> {
							val message = if (event.commResult.containsKey("success")) {
								event.commResult["success"].toString()
							} else {
								"通信成功"
							}
							SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar(message, SharedEvent.Color.Success, Snackbar.LENGTH_INDEFINITE))
							SharedEvent.eventFlow.emit(SharedEvent.PlayEffect())
							updateCsvRowsByCommResult(event.commResult) // 依據結果更新csvRows
						}

						is CommEndEvent.Error -> {
							val message = if (event.commResult.containsKey("error")) {
								event.commResult["error"].toString()
							} else {
								event.commResult.toString()
							}
							SharedEvent.eventFlow.emit(SharedEvent.ShowSnackbar(message, SharedEvent.Color.Error, Snackbar.LENGTH_INDEFINITE))
							SharedEvent.eventFlow.emit(SharedEvent.PlayEffect())
							updateCsvRowsByCommResult(event.commResult) // 依據結果更新csvRows
						}

						else -> {}
					}
				}
			}
		}
	}

	// cb
	private var onBluetoothOn:(() -> Unit)? = null
	private var onConnected:(() -> Unit)? = null
	private var onConnectionFailed:(() -> Unit)? = null

	// 藍牙請求器
	private val bluetoothRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode == Activity.RESULT_OK) {
			this.onBluetoothOn?.invoke()
			this.onBluetoothOn = null
		}
		this.onBluetoothOn = null
	}

	// 檢查藍牙是否開啟
	fun checkBluetoothOn(onConnected:() -> Unit) {
		this.onBluetoothOn = { checkReadyCommunicate(onConnected) }
		if (!blVM.isBluetoothOn()) {
			blVM.checkBluetoothOn(bluetoothRequestLauncher)
		} else {
			onBluetoothOn?.invoke()
			onBluetoothOn = null
		}
	}

	// 檢查能不能進行通信
	private fun checkReadyCommunicate(onConnected:() -> Unit) {
		when (blVM.commStateFlow.value) {
			CommState.NotConnected -> autoConnectDevice(onConnected)
			CommState.ReadyCommunicate -> onConnected.invoke()
			CommState.Connecting -> {}
			CommState.Communicating -> {}
			else -> {}
		}
	}

	// 如果有連線過的設備,直接嘗試連線, 沒有若連線失敗則開藍牙窗
	private fun autoConnectDevice(onConnected:() -> Unit) {
		if (blVM.autoConnectDeviceStateFlow.value != null) {
			this.onConnectionFailed = { BtDialogFragment.open(requireContext()) }
			this.onConnected = onConnected
			blVM.connectDevice()
		} else {
			this.onConnected = onConnected
			BtDialogFragment.open(requireContext())
		}
	}

	// 根據結果更新csvRows & ftp紀錄log
	private suspend fun updateCsvRowsByCommResult(commResult:Map<String, Any>) {
		SharedEvent.catching {
			var newCsvRows = meterVM.meterRowsStateFlow.value
			//todo 目前只有處理D05m
			if (commResult.containsKey("D05m")) {
				val d05mList = (commResult["D05m"] as D05mInfo).list
				newCsvRows = newCsvRows.map { meterRow ->
					val d05Info = d05mList.find { it.meterId == meterRow.meterId }
					if (d05Info != null) {
						val batteryVoltageDropAlarm = try {
							d05Info.alarmInfoDetail["A8"]!!["b4"]!! ||
									d05Info.alarmInfoDetail["A4"]!!["b4"]!! ||
									d05Info.alarmInfoDetail["A4"]!!["b3"]!!
						} catch (e:Exception) {
							null
						}
						meterRow.copy(
							isManualMeterDegree = false,
							meterDegree = d05Info.meterDegree,
							meterReadTime = TimeUtil.getCurrentTime(),
							alarmInfo1 = d05Info.alarmInfo1,
							batteryVoltageDropAlarm = batteryVoltageDropAlarm,
							innerPipeLeakageAlarm = d05Info.alarmInfoDetail["A5"]?.get("b1"),
							shutoff = d05Info.alarmInfoDetail["A1"]?.get("b1")?.not(),
							electricFieldStrength = d05Info.electricFieldStrength,
						)
					} else {
						meterRow
					}
				}
			}
			// todo ftp log 紀錄有更新瓦斯表的功能
			// ...
			// 更新ui並儲存csv
			csvVM.updateSaveCsv(newCsvRows, meterVM)
		}
	}

	// 手動更新csv (用group & meterId找)
	fun updateCsvRowManual(newMeterRow:MeterRow, _origMeterId:String? = null) {
		val origMeterId = _origMeterId ?: newMeterRow.meterId
		val newCsvRows = meterVM.meterRowsStateFlow.value.map { meterRow ->
			if (meterRow.group == newMeterRow.group && meterRow.meterId == origMeterId) {
				newMeterRow
			} else {
				meterRow
			}
		}
		csvVM.updateSaveCsv(newCsvRows, meterVM)
	}
	//endregion
}


// 分頁管理器
class MeterPageAdapter(fragment:Fragment) : FragmentStateAdapter(fragment) {

	override fun getItemCount():Int = 3

	override fun createFragment(position:Int):Fragment {
		val fragment = when (position) {
			0 -> MeterGroupsFragment()
			1 -> MeterListFragment()
			2 -> MeterRowFragment()
			else -> MeterGroupsFragment()
		}
		return fragment
	}
}
