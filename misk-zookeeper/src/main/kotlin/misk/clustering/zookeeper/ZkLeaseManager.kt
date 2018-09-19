package misk.clustering.zookeeper

import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.ThreadFactoryBuilder
import misk.DependentService
import misk.clustering.Cluster
import misk.clustering.leasing.Lease
import misk.clustering.leasing.LeaseManager
import misk.config.AppName
import misk.logging.getLogger
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.framework.state.ConnectionStateListener
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.annotation.concurrent.GuardedBy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ZkLeaseManager @Inject internal constructor(
  @AppName private val appName: String,
  internal val cluster: Cluster,
  curator: CuratorFramework
) : AbstractIdleService(), LeaseManager, DependentService, ConnectionStateListener {
  override val consumedKeys = setOf(ZookeeperModule.serviceKey)
  override val producedKeys = setOf(ZookeeperModule.leaseManagerKey)

  internal val client = curator.usingNamespace(appName.asZkNamespacePath)
  private val executor = Executors.newCachedThreadPool(ThreadFactoryBuilder()
      .setNameFormat("lease-monitor-%d")
      .build())

  enum class State {
    NOT_STARTED,
    RUNNING,
    STOPPED
  }

  @GuardedBy("this") private var connected = false
  @GuardedBy("this") private var state = State.NOT_STARTED
  @GuardedBy("this") private val leases = mutableMapOf<String, ZkLease>()

  internal val isConnected get() = synchronized(this) { connected }
  internal val isRunning get() = synchronized(this) { state == State.RUNNING } && client.isRunning

  override fun startUp() {
    log.info { "starting zk lease manager" }
    synchronized(this) {
      check(state == State.NOT_STARTED) { "attempting to start lease manager in $state state" }

      state = State.RUNNING
      client.connectionStateListenable.addListener(this)
      cluster.watch { handleClusterChange() }
    }
  }

  override fun shutDown() {
    log.info { "stopping zk lease manager" }
    synchronized(this) {
      check(state == State.RUNNING) { "attempting to stop lease manager in $state state" }
      state = State.STOPPED

      leases.values.forEach { lease ->
        executor.submit { lease.releaseIfHeld(false) }
      }
    }

    executor.shutdown()
    executor.awaitTermination(15, TimeUnit.SECONDS)
  }

  override fun tryAcquireLease(name: String, ttl: Duration): Lease {
    return synchronized(this) {
      check(state != State.STOPPED) { "attempting to acquire lease from $state lease manager" }
      leases.computeIfAbsent(name) { ZkLease(this, name) }
    }
  }

  private fun handleClusterChange() {
    // Reconfirm whether we should own each lease now that the cluster topology has changed
    val leasesToCheck = synchronized(this) { leases.values }
    leasesToCheck.forEach { lease ->
      executor.submit {
        lease.checkHeld()
      }
    }
  }

  override fun stateChanged(client: CuratorFramework, newState: ConnectionState) {
    synchronized(this) {
      connected = newState.isConnected
      if (!connected) {
        // Mark each lease as being in an UNKNOWN state
        leases.values.forEach { lease -> lease.connectionLost() }
      }
    }
  }

  companion object {
    private val log = getLogger<ZkLeaseManager>()
  }
}