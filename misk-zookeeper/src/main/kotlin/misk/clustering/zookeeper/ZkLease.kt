package misk.clustering.zookeeper

import misk.clustering.leasing.Lease
import misk.logging.getLogger
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.ZooDefs
import javax.annotation.concurrent.GuardedBy

/**
 * Zookeeper based load balanced lease.
 *
 * <p>Multiple servers use a [misk.clustering.ClusterHashRing] to determine which process should
 * own a lease. As long as each process has the same view of the cluster will be consistent and
 * processes will not actively compete for the same lease. When the process list changes, the
 * process hashing will change en masse.</p>
 *
 * <p>A lease is acquired by creating an ephemeral node, if it doesn't already exist. Once acquired,
 * it will not be released until the process deletes the node. Each time {@link #checkHeld()}
 * is called, a check is made against the current process hash to determine if the lease should
 * still hold the lease, and if not, it will release the lease, allowing the correctly hashed
 * process to acquire the lease subsequently.
 *
 * <p>TODO(mmihic): Support explicit disabling via zk nodes
 *
 * <p> Once acquired, the lease will generally remain held as long as the lease holder process
 * remains running. However, under rare conditions, such as a network partition or a jvm pause,
 * the lease may expire if zookeeper doesn't hear from client before the zookeeper session timeout
 * passes. This means that under certain circumstances, the leadership durability is tied to the
 * zookeeper session timeout.
 *
 * <p>
 * Users of this lease framework should avoid creating tasks that have a duration longer than
 * the session timeout or they risk having a split brain running two leader tasks on different hosts
 * simultaneously.
 */
internal class ZkLease(private val manager: ZkLeaseManager, override val name: String) : Lease {
  enum class Status {
    UNKNOWN,
    NOT_HELD,
    HELD
  }

  @GuardedBy("this") private var status: Status = Status.UNKNOWN
  private val leaseData = name.toByteArray(Charsets.UTF_8)

  override fun checkHeld(): Boolean {
    try {
      // TODO(mmihic): Allow explicit disabling of individual leases and individual lease ownership
      // pinned to a specific server
      if (!manager.isRunning || !manager.isConnected) {
        return false
      }

      // Check whether we should own the lease
      val clusterSnapshot = manager.cluster.snapshot
      val leaseOwner = clusterSnapshot.hashRing.mapResourceToMember(name)
      if (leaseOwner.name != clusterSnapshot.self.name) {
        // We should no longer hold the lease, so release it if we do
        releaseIfHeld(true)
        return false
      }

      // We should own the lease. Check if we do, and if not then attempt to acquire it
      synchronized(this) {
        if (status == Status.HELD) {
          // We already own the lease
          return true
        }

        // See if we still have the lease according to zookeeper - we may have forgotten this
        // as a result of a spurious disconnect / reconnect to zookeeper
        if (checkLeaseNodeExists()) {
          if (checkLeaseDataMatches()) {
            log.info { "reclaiming currently held lease $name" }
            status = Status.HELD
            return true
          }

          leaseHeldByAnother()
          return false
        }

        // The lease node doesn't exist, so try to acquire it
        return tryAcquireLeaseNode()
      }
    } catch (e: Exception) {
      log.error(e) { "unexpected exception checking if lease $name is held" }
      return false
    } catch (e: Error) {
      log.error(e) { "unexpected error checking if lease $name is held" }
      throw e
    }
  }

  fun connectionLost() {
    synchronized(this) {
      // We're disconnected from the cluster, so we don't know the state of the lease. Zk will
      // automatically expire the lease if we remain disconnected for too long, and in the
      // meanwhile we should act as though the lease has been lost
      status = Status.UNKNOWN
    }
  }

  fun releaseIfHeld(guaranteeDelete: Boolean = true) {
    synchronized(this) {
      if (status == Status.NOT_HELD) {
        // We're not holding the lease, nothing to be done
        return
      }

      try {
        if (checkLeaseNodeExists() && checkLeaseDataMatches()) {
          // Lease exists and we own it, so delete it
          if (guaranteeDelete) {
            manager.client.delete().guaranteed().forPath(name)
          } else {
            manager.client.delete().forPath(name)
          }

          log.info { "released lease $name" }
        }
      } catch (e: KeeperException.NoNodeException) {
        // ignore, node already deleted possibly due to race condition, such as when we shutdown
        // while the node is already being released due to cluster change or disconnection
      } catch (e: Exception) {
        status = Status.UNKNOWN
        log.warn(e) {
          "received unexpected exception while releasing lease $name, status is ${Status.UNKNOWN}"
        }
      }
    }
  }

  private fun tryAcquireLeaseNode(): Boolean {
    try {
      manager.client.create()
          .withMode(CreateMode.EPHEMERAL)
          .withACL(ZooDefs.Ids.OPEN_ACL_UNSAFE)
          .forPath(name, leaseData)
      status = Status.HELD
      log.info { "acquired lease $name" }
      return true
    } catch (e: KeeperException.NodeExistsException) {
      leaseHeldByAnother()
    } catch (e: Exception) {
      log.info(e) { "got unexpected exception trying to acquire node for lease $name" }
    }

    return false
  }

  private fun checkLeaseNodeExists() = manager.client.checkExists().forPath(name) != null
  private fun checkLeaseDataMatches() = leaseData.contentEquals(currentLeaseData() ?: byteArrayOf())
  private fun currentLeaseData() = try {
    manager.client.data.forPath(name)
  } catch (e: KeeperException.NoNodeException) {
    // The lease node no longer exists
    null
  }

  private fun leaseHeldByAnother() {
    synchronized(this) {
      if (status != Status.NOT_HELD) {
        status = Status.NOT_HELD
        log.info { "updating status for lease $name to ${Status.NOT_HELD}; it is held by another process" }
      } else {
        log.debug { "lease $name is held by another process; skipping acquiring" }
      }
    }
  }

  companion object {
    private val log = getLogger<ZkLease>()
  }
}