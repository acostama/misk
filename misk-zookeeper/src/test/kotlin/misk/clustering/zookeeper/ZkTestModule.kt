package misk.clustering.zookeeper

import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.Service
import com.google.inject.Key
import misk.DependentService
import misk.clustering.Cluster
import misk.clustering.fake.FakeCluster
import misk.config.AppName
import misk.inject.KAbstractModule
import misk.inject.asSingleton
import misk.inject.keyOf
import misk.service.ServiceTestingModule
import org.apache.curator.framework.CuratorFramework
import javax.inject.Singleton

internal class ZkTestModule : KAbstractModule() {
  override fun configure() {
    bind<ZookeeperConfig>().toInstance(sharedZookeeper.config)
    bind<CuratorFramework>().toProvider(CuratorFrameworkProvider::class.java).asSingleton()
    bind<Cluster>().to<FakeCluster>()
    bind<String>().annotatedWith<AppName>().toInstance("my-app")

    multibind<Service>().to<ZkService>()
    multibind<Service>().to<FakeCluster>()
    multibind<Service>().to<StartZookeeperService>()
    install(ServiceTestingModule.withExtraDependencies<ZkLeaseManager>(startZkServiceKey))
  }

  @Singleton
  class StartZookeeperService : AbstractIdleService(), DependentService {

    override val consumedKeys: Set<Key<*>> = setOf()
    override val producedKeys: Set<Key<*>> = setOf(keyOf<StartZookeeperService>())

    override fun startUp() {
      sharedZookeeper.start()
    }

    override fun shutDown() {
      sharedZookeeper.stop()
    }
  }

  companion object {
    private val startZkServiceKey = keyOf<StartZookeeperService>()
    private val sharedZookeeper = EmbeddedZookeeper(29000)
  }
}