package com.example.network

object DsuProtocol {
    const val MAGIC_DSUS = 0x53555344 // "DSUS" in Little Endian (or "DSUS" string bytes)
    const val PROTOCOL_VERSION = 1001
    
    // Message Types
    const val MSG_TYPE_VERSION = 0x100000
    const val MSG_TYPE_PORTS = 0x100001
    const val MSG_TYPE_INPUT = 0x100002
    const val MSG_TYPE_OUTPUT = 0x100003

    // Port definition
    const val DEFAULT_PORT = 26760
}
