package shop.whitedns.client.vpn

import shop.whitedns.client.model.WhiteDnsOptions

internal data class VpnApplicationRouting(
    val allowedPackages: List<String> = emptyList(),
    val disallowedPackages: List<String> = emptyList(),
)

internal fun vpnApplicationRouting(
    mode: String,
    packages: List<String>,
    ownPackage: String,
): VpnApplicationRouting {
    val selected = packages.map(String::trim)
        .filter { it.isNotEmpty() && it != ownPackage }
        .distinct()
    return when (mode) {
        WhiteDnsOptions.SplitTunnelModeInclude -> {
            require(selected.isNotEmpty()) {
                "Split tunnel include mode requires at least one selected app"
            }
            // An allowlist excludes all other UIDs, including our native DNS
            // engine. Android prohibits mixing allowed and disallowed apps.
            VpnApplicationRouting(allowedPackages = selected)
        }
        WhiteDnsOptions.SplitTunnelModeExclude ->
            VpnApplicationRouting(disallowedPackages = listOf(ownPackage) + selected)
        else -> VpnApplicationRouting(disallowedPackages = listOf(ownPackage))
    }
}
