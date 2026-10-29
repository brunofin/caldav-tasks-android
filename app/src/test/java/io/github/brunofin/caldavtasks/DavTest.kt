package io.github.brunofin.caldavtasks

import org.junit.Test

class DavTest {
    @Test fun protocolSafety() = DavChecks.runAll()
}
