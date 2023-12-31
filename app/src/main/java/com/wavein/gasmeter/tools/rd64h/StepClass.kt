package com.wavein.gasmeter.tools.rd64h

// 傳收步驟class
open class BaseStep
data class RTestStep(val text:String) : BaseStep()
class DTestStep : BaseStep()

class __AStep : BaseStep()
class __5Step : BaseStep()
class D70Step : BaseStep()
data class R80Step(val meterIds:List<String>) : BaseStep()
data class D05Step(val count:Int) : BaseStep()

data class R89Step(val meterId:String) : BaseStep()
class D36Step : BaseStep()
data class R70Step(val meterId:String) : BaseStep()

// R87, 參數說明請參考 ALine.kt
data class R87Step(val cc:String = "\u0021\u0040\u0000\u0000", val adr:String, val op:String, val data:String = "") : BaseStep()
class D87D01Step : BaseStep()
class D87D05Step : BaseStep()
class D87D23Step : BaseStep()



