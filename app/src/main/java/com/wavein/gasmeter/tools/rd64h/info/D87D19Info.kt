package com.wavein.gasmeter.tools.rd64h.info

import com.wavein.gasmeter.tools.rd64h.ALine
import com.wavein.gasmeter.tools.rd64h.SecurityLevel

data class D87D19Info(override val text:String) : BaseInfo(text) {
	var data:String = "" // 時刻(19-01) 12位

	init {
		val matchResult = Regex("^ZD(.{14})D87(.+)$").find(text) ?: throw Exception("異常")
		val (meterId, aLineRaw) = matchResult.destructured
		val aLine = ALine(aLineRaw, SecurityLevel.Auth)
		data = aLine.data
	}

	override fun toString():String {
		return data
	}
}