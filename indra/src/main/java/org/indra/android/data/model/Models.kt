package org.indra.android.data.model

data class Network(
    val name: String,
    val interfaceName: String,
    val localAddress: String,
    val gatewayAddress: String,
    val isConnected: Boolean
)

data class Target(
    val address: String,
    val name: String,
    val type: TargetType,
    val os: String? = null,
    val openPorts: List<Int> = emptyList(),
    val services: List<Service> = emptyList()
)

enum class TargetType {
    NETWORK, ENDPOINT, SERVICE
}

data class Service(
    val port: Int,
    val protocol: String,
    val name: String,
    val version: String? = null
)

data class Exploit(
    val id: String,
    val name: String,
    val description: String,
    val platform: String
)

data class Session(
    val id: String,
    val target: String,
    val type: String
)
