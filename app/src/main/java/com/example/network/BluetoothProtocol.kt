package com.example.network

object BluetoothProtocol {
    const val SYNC_HEADER_1 = 0xAA.toByte()
    const val SYNC_HEADER_2 = 0x55.toByte()
    
    const val PACKET_SIZE = 32
    const val BODY_SIZE = 30

    const val MAX_SENDERS = 3
}
