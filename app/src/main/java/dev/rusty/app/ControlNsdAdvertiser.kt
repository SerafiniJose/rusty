package dev.rusty.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Android glue for advertising the remote-control API over NSD (`_rusty._tcp`), so Home
 * Assistant's zeroconf discovery can find this device without the user typing an IP. The pure
 * "should we advertise this URL", "register / re-register / leave alone" and "what goes in the
 * TXT record" decisions live in [ControlNsdPlan]; this class is the thin, untestable-on-the-JVM
 * wrapper around `NsdManager` that [ControlService] drives with the outcome of those decisions —
 * verified by build + on-device acceptance only (see Task 10's report).
 *
 * ## The register/unregister race
 * `NsdManager.registerService`/`unregisterService` are asynchronous: they enqueue work and return
 * immediately, with [NsdManager.RegistrationListener] callbacks landing later on a callback
 * thread. That means a call to [register] can still be in flight — the listener object exists,
 * but the daemon has not adopted it yet — when [unregister] is called for the same instance (a
 * service teardown racing a late network-change re-registration is the real case). [lock] plus
 * [activeListener] resolve this deterministically: whichever of [register]/[unregister] runs last
 * is the one whose listener ends up live, and an unregister of a listener the daemon never
 * finished adopting is exactly the `IllegalArgumentException` `NsdManager.unregisterService` is
 * documented to throw for "not registered" — caught, not propagated, so it can never crash a
 * service teardown.
 */
class ControlNsdAdvertiser(private val context: Context) {

    private companion object {
        const val TAG = "ControlNsdAdvertiser"
    }

    private val lock = Any()

    /** Guarded by [lock]. The listener [register] most recently handed to `NsdManager`, or null
     *  once [unregister] has claimed it. Tracked from the moment [register] is called (not from
     *  `onServiceRegistered`), so an [unregister] issued before the daemon has even replied still
     *  knows there is something to tear down. */
    private var activeListener: NsdManager.RegistrationListener? = null

    /**
     * Registers `serviceName = `[deviceName]`, serviceType = `[ControlNsdPlan.SERVICE_TYPE]`,
     * port = `[port]`, TXT = `[ControlNsdPlan.txtAttributes]. Any registration already in flight
     * for this advertiser is unregistered first — [ControlNsdPlan.action] answers [ControlNsdPlan
     * .Action.Register] for both "nothing was registered" and "a different URL is now current", so
     * this method, not the caller, is where a stale registration gets dropped.
     *
     * `onRegistrationFailed` only logs: per the design doc, discovery failure must not present as
     * a broken feature — the API stays reachable by manual IP, and [ControlServerStatus] is left
     * exactly as [ControlService] already published it.
     */
    fun register(deviceName: String, port: Int, deviceId: String) {
        val manager = context.getSystemService(NsdManager::class.java)
        if (manager == null) {
            Log.w(TAG, "NsdManager unavailable; control API will only be reachable by manual IP")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = ControlNsdPlan.SERVICE_TYPE
            this.port = port
            ControlNsdPlan.txtAttributes(deviceId, deviceName).forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Android silently suffixes the name on collision ("Rusty Speaker (2)") — THIS is
                // the real registered name. Logged only: nothing downstream currently needs it
                // back (the settings row shows the configured name, not the mDNS instance name),
                // but it is the one place that name is ever visible for on-device diagnosis.
                Log.i(TAG, "NSD registered as \"${info.serviceName}\" on port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed for \"${info.serviceName}\" (error $errorCode)")
                // This listener never got adopted by the daemon; drop it so a later unregister()
                // does not try to tear down a registration that never existed.
                synchronized(lock) { if (activeListener === this) activeListener = null }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "NSD unregistered \"${info.serviceName}\"")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed for \"${info.serviceName}\" (error $errorCode)")
            }
        }

        val previous = synchronized(lock) {
            val old = activeListener
            activeListener = listener
            old
        }
        // A prior registration is still tracked as active — this is a re-register (the address
        // changed) rather than a fresh one. Drop it first; best-effort, same as unregister().
        if (previous != null) unregisterListener(manager, previous)

        runCatching { manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { e ->
                Log.w(TAG, "Failed to submit NSD registration", e)
                synchronized(lock) { if (activeListener === listener) activeListener = null }
            }
    }

    /**
     * Idempotent and never throws. Safe to call with nothing registered (a service that never
     * reached a routable address) and safe to call twice (an already-unregistered listener, or one
     * [onRegistrationFailed] already dropped) — both would otherwise hit `NsdManager
     * .unregisterService`'s `IllegalArgumentException` for "not registered".
     */
    fun unregister() {
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        val listener = synchronized(lock) {
            val l = activeListener
            activeListener = null
            l
        }
        if (listener != null) unregisterListener(manager, listener)
    }

    private fun unregisterListener(manager: NsdManager, listener: NsdManager.RegistrationListener) {
        runCatching { manager.unregisterService(listener) }
            .onFailure { e -> Log.w(TAG, "NSD unregister raced registration; ignoring", e) }
    }
}
