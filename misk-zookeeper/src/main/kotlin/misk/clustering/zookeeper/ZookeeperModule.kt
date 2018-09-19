package misk.clustering.zookeeper

import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.inject.Key
import com.google.inject.Provides
import misk.inject.KAbstractModule
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import javax.inject.Singleton

class ZookeeperModule : KAbstractModule() {
  override fun configure() {
    multibind<Service>().to<ZkService>()
    multibind<Service>().to<ZkLeaseManager>()
  }

  @Provides @Singleton
  fun provideCuratorFramework(config: ZookeeperConfig): CuratorFramework {
    // Uses reasonable default values from http://curator.apache.org/getting-started.html
    val retryPolicy = ExponentialBackoffRetry(1000, 3)
    return CuratorFrameworkFactory.builder()
        .connectString(config.zk_connect)
        .retryPolicy(retryPolicy)
        .sessionTimeoutMs(config.session_timeout_msecs)
        .canBeReadOnly(false)
        .threadFactory(ThreadFactoryBuilder()
            .setNameFormat("zk-clustering-${config.zk_connect}")
            .build())
        .build()
  }

  companion object {
    /** @property Key<*> The key of the service which manages the zk connection, for service dependencies */
    val serviceKey: Key<*> = Key.get(ZkService::class.java) as Key<*>

    /** @property Key<*> the Key of the lease manager service */
    val leaseManagerKey: Key<*> = Key.get(ZkLeaseManager::class.java)
  }
}