package com.rtree.LIFELiNK

interface EmergencyTrigger {
    val wireValue: String
}

data object ScreenButtonEmergencyTrigger : EmergencyTrigger {
    override val wireValue: String = "screen_button"
}

data object BleEmergencyTrigger : EmergencyTrigger {
    override val wireValue: String = "ble"
}
