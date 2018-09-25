package misk.clustering.zookeeper

import com.google.inject.util.Modules
import misk.MiskServiceModule
import misk.testing.MiskTest
import misk.testing.MiskTestModule
import org.junit.jupiter.api.Test

@MiskTest(startService = true)
internal class ZkLeaseTest {
  @MiskTestModule private val module = Modules.combine(
      MiskServiceModule(),
      ZkTestModule()
  )

  @Test
  fun checkLease() {

  }
}