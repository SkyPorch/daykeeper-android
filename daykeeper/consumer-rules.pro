# androidx.startup instantiates this initializer reflectively from the merged manifest during host
# app startup. startup-runtime ships an equivalent rule for every Initializer; this explicit copy
# keeps Daykeeper's own class even if a host or tool drops or overrides that dependency's rules.
-keep class com.skyporch.daykeeper.DaykeeperContextInitializer {
    public <init>();
}
