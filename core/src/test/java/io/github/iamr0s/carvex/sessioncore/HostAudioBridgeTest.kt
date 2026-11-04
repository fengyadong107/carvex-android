package io.github.iamr0s.carvex.sessioncore

import io.github.iamr0s.carvex.pcm.PcmFormat
import io.github.iamr0s.carvex.pcm.Pcm16Converter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HostAudioBridgeTest {
    private class CapturingStream(private val pcmFormat:PcmFormat,
        private val beforeSend:suspend ()->Unit={}):NegotiatedAudioTransport {
        private val output=kotlinx.coroutines.channels.Channel<ByteArray>(8)
        @Volatile private var closed=false
        override val ready get()=!closed
        override val formats=mapOf(3 to pcmFormat)
        override val microphoneFormat:PcmFormat?=null
        override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){
            assertEquals(3,category);assertEquals(pcmFormat,format);beforeSend();output.send(pcm)
        }
        suspend fun next():ByteArray=withTimeout(2000){output.receive()}
        override fun close(){closed=true;output.cancel()}
    }

    @Test fun fresh120msSpeechCallbackIsForwardedWholeInsteadOfLosingItsFirst20ms():Unit=runBlocking {
        val source=PcmFormat(16000,1)
        val stream=CapturingStream(source)
        val bridge=HostAudioBridge({},configureRoute={})
        val pcm=ByteArray(3840){it.toByte()}
        try{
            bridge.attach(stream);bridge.receive(pcm,3,source)
            assertArrayEquals(pcm,stream.next())
            assertEquals(0L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }

    @Test fun resampledSpeechRetainsEveryFreshBlockAndConverterPhase():Unit=runBlocking {
        val source=PcmFormat(16000,1);val target=PcmFormat(48000,1)
        val stream=CapturingStream(target)
        val bridge=HostAudioBridge({},configureRoute={})
        val reference=Pcm16Converter(source,target)
        try{
            bridge.attach(stream)
            repeat(3){block->
                val pcm=ByteArray(3840){(it+block*31).toByte()}
                bridge.receive(pcm,3,source)
                assertArrayEquals(reference.convert(pcm),stream.next())
            }
            assertEquals(0L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }

    @Test fun callbackContinuityAccountsFor120msBlocksAndNormalJitter(){
        val source=PcmFormat(16000,1)
        assertFalse(pcmCallbackHasGap(null,0,source,3840))
        assertFalse(pcmCallbackHasGap(0,120_000_000,source,3840))
        assertFalse(pcmCallbackHasGap(0,150_000_000,source,3840))
        assertFalse(pcmCallbackHasGap(0,220_000_000,source,3840))
        assertTrue(pcmCallbackHasGap(0,220_000_001,source,3840))
        // Callback completion spacing follows the current block's duration,
        // including a change from a short block to a long one and vice versa.
        assertFalse(pcmCallbackHasGap(40_000_000,160_000_000,source,3840))
        assertFalse(pcmCallbackHasGap(160_000_000,200_000_000,source,1280))
        assertTrue(pcmCallbackHasGap(160_000_000,500_000_000,source,1280))
    }

    @Test fun preservingFreshSpeechDoesNotRemoveFrameAlignmentOrAllocationBounds(){
        val bridge=HostAudioBridge({},configureRoute={})
        try{
            assertThrows(IllegalArgumentException::class.java){
                bridge.receive(ByteArray(32770),3,PcmFormat(16000,1))
            }
            assertThrows(IllegalArgumentException::class.java){
                bridge.receive(ByteArray(3841),3,PcmFormat(16000,1))
            }
            assertEquals(0L,bridge.snapshot().received)
        }finally{bridge.close()}
    }

    @Test fun fullSpeechBlocksStillExpireInASlowQueueAndFreshSpeechRecovers():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val calls=AtomicInteger();val source=PcmFormat(16000,1)
        val stream=CapturingStream(source){
            if(calls.incrementAndGet()==1){entered.complete(Unit);release.await()}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        val first=ByteArray(3840){1};val stale=ByteArray(3840){2};val fresh=ByteArray(3840){3}
        try{
            bridge.attach(stream);bridge.receive(first,3,source)
            withTimeout(2000){entered.await()}
            bridge.receive(stale,3,source)
            delay(150) // Actual queue age, not the 120 ms represented by this PCM block.
            release.complete(Unit)
            assertArrayEquals(first,stream.next())
            withTimeout(2000){while(bridge.snapshot().dropped==0L)delay(1)}
            bridge.receive(fresh,3,source)
            assertArrayEquals(fresh,stream.next())
            assertEquals(2,calls.get());assertEquals(1L,bridge.snapshot().dropped)
        }finally{release.complete(Unit);bridge.close()}
    }

    private class Stream(category:Int,val fail:Boolean=false):NegotiatedAudioTransport {
        @Volatile var closed=false
        val sent=AtomicInteger()
        override val ready get()=!closed
        override val formats=mapOf(category to PcmFormat(48000,2))
        override val microphoneFormat:PcmFormat?=null
        override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){if(fail)error("broken audio socket");sent.incrementAndGet()}
        override fun close(){closed=true}
    }
    @Test fun alternateAttachmentAndFailureLeaveMediaAlive()=runBlocking {
        val bridge=HostAudioBridge({},configureRoute={})
        val media=Stream(1);val alternate=Stream(2,true)
        try{
            bridge.attach(media);bridge.attach(alternate)
            assertEquals(setOf(1,2),bridge.categories());assertFalse(media.closed)
            bridge.receive(ByteArray(960),2,PcmFormat(48000,2))
            withTimeout(2000){while(!alternate.closed)delay(1)}
            assertEquals(setOf(1),bridge.categories());assertFalse(media.closed)
            bridge.receive(ByteArray(960),1,PcmFormat(48000,2))
            withTimeout(2000){while(media.sent.get()==0)delay(1)}
        }finally{bridge.close()}
        assertTrue(media.closed);assertTrue(alternate.closed)
    }
    @Test fun failedRouteConfigurationRestoresExistingCategories()=runBlocking {
        var attempts=0
        val bridge=HostAudioBridge({},configureRoute={if(++attempts==2)error("host rejected route")})
        val media=Stream(1);val alternate=Stream(2)
        try{
            bridge.attach(media)
            try{bridge.attach(alternate);fail("must reject")}catch(_:IllegalStateException){}
            assertEquals(setOf(1),bridge.categories());assertFalse(media.closed);assertTrue(alternate.closed)
        }finally{bridge.close()}
    }
    private class Microphone:MicrophoneInput {
        @Volatile var closed=false
        override val ready get()=!closed
        override fun close(){closed=true}
    }
    @Test fun microphoneFailureRevokesAdvertisedReadiness():Unit=runBlocking {
        val bridge=HostAudioBridge({},configureRoute={})
        val microphone=Microphone()
        try{
            assertFalse(bridge.microphoneReady())
            bridge.attachMicrophone(microphone,PcmFormat(16000,1))
            assertTrue(bridge.microphoneReady())
            microphone.close()
            assertFalse(bridge.microphoneReady())
            assertFalse(bridge.microphone(ByteArray(320),PcmFormat(16000,1)))
        }finally{bridge.close()}
    }
    @Test fun microphoneReplacementClosesPreviousAndRejectsDeadReceiver():Unit=runBlocking {
        val bridge=HostAudioBridge({},configureRoute={})
        val first=Microphone();val second=Microphone();val dead=Microphone().apply{close()}
        try{
            bridge.attachMicrophone(first,PcmFormat(16000,1))
            bridge.attachMicrophone(second,PcmFormat(24000,1))
            assertTrue(first.closed);assertFalse(second.closed)
            try{bridge.attachMicrophone(dead,PcmFormat(16000,1));fail("dead input must be rejected")}catch(_:IllegalStateException){}
            assertTrue(bridge.microphoneReady());assertFalse(second.closed)
        }finally{bridge.close()}
        assertTrue(second.closed)
    }
    @Test fun preferredMusicRateIsAvailableBeforeDownstreamAttach(){
        val bridge=HostAudioBridge({},configureRoute={})
        try{assertEquals(48000,bridge.musicSampleRate())}finally{bridge.close()}
    }
    @Test fun initialActiveStateAndUnboundPcmAreObservableWithoutInventingTransport(){
        val logs=mutableListOf<String>()
        val bridge=HostAudioBridge({logs+=it},configureRoute={})
        try{
            bridge.state(3,1);bridge.state(3,1)
            bridge.receive(ByteArray(960),3,PcmFormat(24000,1))
            bridge.receive(ByteArray(960),3,PcmFormat(24000,1))
            assertEquals(1,logs.count{it.startsWith("Audio host state observed category=3 state=1")})
            assertEquals(1,logs.count{it.startsWith("Audio source first category=3")&&"attached=false" in it})
            assertFalse(logs.any{"-> resume" in it})
            assertTrue(bridge.categories().isEmpty())
            assertEquals(2L,bridge.snapshot().dropped)
        }finally{bridge.close()}
    }
    @Test fun mediaSilenceKeepsTheStreamWarmUntilRealPcm():Unit=runBlocking {
        val format=PcmFormat(48000,2)
        val got=kotlinx.coroutines.channels.Channel<ByteArray>(8)
        val stream=object:NegotiatedAudioTransport {
            @Volatile var closed=false
            override val ready get()=!closed
            override val formats=mapOf(1 to format)
            override val microphoneFormat:PcmFormat?=null
            override suspend fun send(category:Int,pcm:ByteArray,format:PcmFormat){got.send(pcm.copyOf())}
            override fun close(){closed=true;got.cancel()}
        }
        val bridge=HostAudioBridge({},configureRoute={})
        try{
            bridge.attach(stream)
            val silence=withTimeout(2000){got.receive()}
            assertTrue(silence.isNotEmpty()&&silence.all{it==0.toByte()})
            bridge.receive(ByteArray(960){1},1,format)
            var frame=silence
            withTimeout(2000){while(frame.all{it==0.toByte()})frame=got.receive()}
            assertTrue(frame.any{it!=0.toByte()})
        }finally{bridge.close()}
    }
    @Test fun pauseDiagnosticsSeparateSourceStateFromReceiverPlayback():Unit=runBlocking {
        val logs=java.util.concurrent.CopyOnWriteArrayList<String>()
        val bridge=HostAudioBridge({logs+=it},configureRoute={})
        val media=Stream(1)
        try{
            bridge.attach(media)
            bridge.receive(ByteArray(7680),1,PcmFormat(48000,2))
            withTimeout(2000){while(media.sent.get()==0)delay(1)}
            bridge.state(1,0)
            assertTrue(logs.any{it.startsWith("Audio source category=1")&&"blockMs=40" in it})
            assertTrue(logs.any{it.startsWith("Audio state timing category=1 state=0")&&"lastSendMonoNs=" in it})
            assertTrue(logs.any{"receiver buffered audio not measured" in it})
            assertTrue(media.ready)
        }finally{bridge.close()}
    }
}
