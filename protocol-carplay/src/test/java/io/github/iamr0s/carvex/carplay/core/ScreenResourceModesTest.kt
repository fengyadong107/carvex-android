package io.github.iamr0s.carvex.carplay.core
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ScreenResourceModesTest {
    private fun request(type:Int,extra:Map<String,Any?> = emptyMap())=mapOf("resources" to listOf(mapOf("resourceID" to 1,"transferType" to type)+extra))
    @Test fun borrowedScreenRetainsPermanentOwnerAndRestores(){
        val m=ScreenResourceModes()
        m.commit(requireNotNull(m.propose(request(3))))
        assertEquals(ScreenResourceModes.State(2,1,true),m.state)
        assertNull(m.propose(request(3)))
        m.commit(requireNotNull(m.propose(request(4))))
        assertEquals(ScreenResourceModes.State(),m.state)
        m.commit(requireNotNull(m.propose(request(1))))
        assertEquals(ScreenResourceModes.State(2,2),m.state)
        m.commit(requireNotNull(m.propose(request(2))))
        assertEquals(ScreenResourceModes.State(),m.state)
    }
    @Test fun conditionalConstraintsAndBorrowIdStillRejected(){
        val m=ScreenResourceModes()
        // 100 是无条件那一档，别的值代表请求带条件，当前实现不评估。
        for(key in listOf("transferPriority","takeConstraint","borrowConstraint","unborrowConstraint"))
            assertNull(m.propose(request(1,mapOf(key to 1))))
        assertNull(m.propose(request(1,mapOf("borrowID" to 1))))
        assertNotNull(m.propose(mapOf("resources" to listOf(mapOf("resourceID" to 2,"transferType" to 1)))))
        assertNull(m.propose(request(1)+mapOf("appStates" to listOf(mapOf("appStateID" to 1,"state" to 1)))))
        assertNull(m.propose(mapOf("resources" to listOf(mapOf("resourceID" to 1,"transferType" to 1.5)))))
        assertEquals(ScreenResourceModes.State(),m.state)
    }
    @Test fun receiverConstraintDeclarationIsAccepted(){
        val m=ScreenResourceModes()
        // DiPlay 的 /info 声明原样回带：nice-to-have 与 anytime 都是 100。
        val declaration=mapOf("transferPriority" to 100,"takeConstraint" to 100,"borrowConstraint" to 100,"unborrowConstraint" to 100)
        assertEquals(ScreenResourceModes.State(2,1,true),m.propose(request(3,declaration)))
    }
    @Test fun statusOnlyChangeModesReplyIsNotSentAgain(){
        assertNull(modesChangedNotification(mapOf("status" to 1)))
        val params=ScreenResourceModes().params()
        assertEquals(params,modesChangedNotification(mapOf("status" to 0,"params" to params))?.get("params"))
    }
    @Test fun postReplyHookRunsAfterEventResponseWasWritten(){
        val inbound=ByteArrayOutputStream()
        RtspWire.write(inbound,"POST /command RTSP/1.0",mapOf("CSeq" to "9"),Plist.encode(mapOf("type" to "changeModes")))
        RtspWire.write(inbound,"RTSP/1.0 200 OK",mapOf("CSeq" to "1"))
        val outbound=ByteArrayOutputStream()
        val client=RtspClient(ByteArrayInputStream(inbound.toByteArray()),outbound){200 to Plist.encode(mapOf("status" to 0))}
        var called=false
        client.afterEventReply={_,_,_->
            val written=ByteArrayInputStream(outbound.toByteArray())
            assertEquals("POST /feedback RTSP/1.0",RtspWire.read(written).first)
            val reply=RtspWire.read(written)
            assertEquals(200,reply.status);assertEquals("9",reply.headers["cseq"])
            called=true
        }
        client.request("POST","/feedback")
        assertTrue(called)
    }
}
