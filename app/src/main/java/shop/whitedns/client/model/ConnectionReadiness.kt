package shop.whitedns.client.model

// The app and Quick Settings must accept the same manual or discovery inputs.
internal fun WhiteDnsSettings.hasConnectionResolverSource(): Boolean =
    resolve().resolverEntries.isNotEmpty() || canDiscoverSoarResolvers() || canBootstrapResolversWithCottenMax()
