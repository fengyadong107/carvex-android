package io.github.iamr0s.carvex.carplay

import android.content.Context
import io.github.iamr0s.carvex.carplay.core.IapCommand
import io.github.iamr0s.carvex.carplay.core.IapFields
import io.github.iamr0s.carvex.carplay.core.IapLink
import io.github.iamr0s.carvex.carplay.core.NowPlayingUpdate
import io.github.iamr0s.carvex.carplay.core.PairIdentity
import io.github.iamr0s.carvex.carplay.core.RuntimeIapState
import io.github.iamr0s.carvex.carplay.core.SecurityBinding
import io.github.iamr0s.carvex.carplay.core.SecurityDiagnostic
import io.github.iamr0s.carvex.carplay.core.SecurityFailure
import io.github.iamr0s.carvex.carplay.core.SecurityReason
import io.github.iamr0s.carvex.carplay.core.TouchSample
import io.github.iamr0s.carvex.carplay.core.securityFailure
import io.github.iamr0s.carvex.sessioncore.HandoffSupervisor
import io.github.iamr0s.carvex.sessioncore.Options
import io.github.iamr0s.carvex.sessioncore.Resources
import io.github.iamr0s.carvex.sessioncore.Stage
import io.github.iamr0s.carvex.sessioncore.TransportErrorCategory
import io.github.iamr0s.carvex.sessioncore.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * CarPlay 适配：蓝牙、iAP2、无线交接和 AirSession 都从这里出去。
 * 编排层只提供阶段时钟和宿主回调，再在返回的 session 上接画面和音频。
 */
interface SessionPhase {
    suspend fun <T> run(stage: Stage, millis: Long, block: suspend () -> T): T
}

class CarPlayUp(val session: AirSession, val commitHandoff: () -> Unit)

/** Android 蓝牙把对端关闭报成 IOException，不是 EOFException。交接已开始时这和 EOF 是同一件事。 */
internal fun bluetoothPeerClosed(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.any { thrown ->
        thrown is java.io.EOFException || thrown is java.io.IOException &&
            thrown.message?.let { message ->
                message.contains("read return: -1") || message.contains("read ret: -1") ||
                    message.contains("bt socket closed") || message.contains("socket might closed")
            } == true
    }

/** 热点信息已经收下之后，蓝牙链路拆掉也不再作废这次交接。 */
internal fun handoffContextCurrent(
    attemptActive: Boolean,
    sessionEnded: Boolean,
    failed: Boolean,
    controlSessionLive: Boolean,
    credentialsReady: Boolean,
): Boolean = attemptActive && !sessionEnded && !failed && (controlSessionLive || credentialsReady)

interface CarPlayHost {
    fun log(message: String)
    fun touch(sample: TouchSample): Boolean
    fun key(prefix: String, usage: Int, down: Boolean): Boolean
    fun failure(cause: Throwable)
    fun runtimeChanged(state: RuntimeIapState)
    fun ui(url: String): Boolean
    fun nightMode(mode: Int)
    fun focusOffer()
    fun voice(action: String): Boolean
    fun warn(message: String)
}

class CarPlayRunner(
    private val context: Context,
    private val options: Options,
    private val resources: Resources,
    private val host: CarPlayHost,
) {
    /** 建立蓝牙 RFCOMM 连接（链路层，握手前）。 */
    suspend fun openBluetooth(): android.bluetooth.BluetoothSocket =
        Bootstrap.bluetooth(context, options, resources, host::log)

    /** iAP2 握手（识别/认证/无线交接）；返回协商后的 Wi-Fi 启动信息。 */
    suspend fun negotiate(
        link: IapLink,
        phoneMac: String,
        deviceName: String,
        identity: PairIdentity,
        previous: WifiBootstrap?,
        experimentalIntegrityOnly: Boolean,
        onNowPlayingCommand: ((Int, IapFields) -> Boolean)? = null,
        onDigitalKeyRequest: DigitalKeyMatcher? = null,
        onDeviceWifiInfoRequest: DeviceWifiInfoProvider? = null,
        onCarPlayStartRequest: CarPlayStartRequest? = null,
        onWifiAnnouncement: WifiAnnouncementHandler? = null,
        networkHandoff: IapNetworkHandoff? = null,
    ): WifiBootstrap = Bootstrap.negotiate(
        link, phoneMac, deviceName, identity, previous,
        experimentalIntegrityOnly = experimentalIntegrityOnly,
        log = host::log,
        onNowPlayingCommand = onNowPlayingCommand,
        onDigitalKeyRequest = onDigitalKeyRequest,
        onDeviceWifiInfoRequest = onDeviceWifiInfoRequest,
        onCarPlayStartRequest = onCarPlayStartRequest,
        onWifiAnnouncement = onWifiAnnouncement,
        networkHandoff = networkHandoff,
    )

    /** 加入车机 Wi-Fi 并准备网络路径。 */
    suspend fun openNetwork(bootstrap: WifiBootstrap, onFailure: (Throwable) -> Unit): NetworkPath =
        Bootstrap.network(context, bootstrap, resources, host::log, onFailure)

    suspend fun prepareNetwork(bootstrap:WifiBootstrap,handoff:IapNetworkHandoff,
        join:suspend(WifiBootstrap)->NetworkPath):NetworkHandoffPipeline.Ready<NetworkPath,CarPlayControlPublication> {
        val pipeline=NetworkHandoffPipeline(handoff,join,
            publish={path:NetworkPath->
                resources.own(CarPlayControlPublication(context,path,options,
                    {runCatching{handoff.requireCurrent()}.isSuccess},host::log,host::failure)).also{publication->
                    handoff.configuration()?.endpoint?.addresses.orEmpty().mapNotNull{address->
                        runCatching{Bootstrap.address(path.properties,address)}.getOrNull()
                    }.let(publication::candidates)
                    publication.start()
                }
            },
            discover={path,value->
                val discovery=ReceiverDiscovery(context,path,value,resources,host::log)
                try{
                    discovery.start()
                    val metadata=discovery.await()
                    value.resolved(requireNotNull(metadata.endpoint?.hostAddress),requireNotNull(metadata.port),
                        requireNotNull(metadata.deviceId),metadata.publicKey,metadata)
                }finally{resources.release(discovery)}
            })
        return pipeline.open(bootstrap)
    }

    /** 构造 AirSession；媒体管线仍由编排层通过返回对象驱动（A 方案）。
     *  `localFps` 与 `sourceContextCurrent` 依赖编排层状态，因此作为参数传入而不是放进 host。 */
    fun openSession(
        path: NetworkPath,
        bootstrap: WifiBootstrap,
        security: SecurityBinding,
        localFps: (Int, Int, Int) -> Int?,
        sourceContextCurrent: () -> Boolean,
        controlPublication: CarPlayControlPublication? = null,
    ): AirSession = AirSession(
        context, path, bootstrap, options, resources,
        host::log,
        host::touch,
        host::key,
        host::failure,
        host::runtimeChanged,
        host::ui,
        host::nightMode,
        host::focusOffer,
        host::voice,
        host::warn,
        localFps,
        security,
        sourceContextCurrent,
        controlPublication = controlPublication,
    )

    /** 建到 AirSession 为止。调用方 prepare 成功后再 commitHandoff。 */
    suspend fun bringUp(
        sessionId: String,
        deadlines: ScheduledExecutorService,
        scope: CoroutineScope,
        phase: SessionPhase,
        attemptActive: () -> Boolean,
        sessionEnded: () -> Boolean,
        failed: CompletableDeferred<Throwable>,
        previousBootstrap: WifiBootstrap?,
        onBootstrap: (WifiBootstrap) -> Unit,
        localFps: (Int, Int, Int) -> Int?,
    ): CarPlayUp {
        val bt=phase.run(Stage.BLUETOOTH,90000){openBluetooth()}
        val pendingSourceEof=AtomicReference<Throwable?>(null)
        val sourceFailure=AtomicReference<(Throwable)->Unit>({error->
            if(bluetoothPeerClosed(error))pendingSourceEof.compareAndSet(null,error)
            else host.failure(error)
        })
        val link=resources.own(IapLink(bt.inputStream,bt.outputStream,{host.log(it)},{sourceFailure.get().invoke(it)}))
        phase.run(Stage.DETECT,25000){
            try{link.start(allowDirectSyn=true)}
            catch(t:Exception){
                // 对端连上就关：下一次对这个车机只监听，把唯一能走 iAP 的方向让出来。
                if(bluetoothPeerClosed(t))Bootstrap.markPreferInbound(options.target)
                throw t
            }
        }
        val identity=PairStore(context).identity()
        val credentialsReady=AtomicBoolean(false)
        val networkHandoff=resources.own(IapNetworkHandoff(link.generation,link.linkEpoch,options.experiment,
            {handoffContextCurrent(attemptActive(),sessionEnded(),failed.isCompleted,
                link.negotiatedControlSession!=null,credentialsReady.get())},
            host::log))
        // The selected control schema owns the transaction order. Authentication failure
        // never retries another order or reuses the same socket's security state.
        var bootstrap=phase.run(Stage.AUTH,90000){
            negotiate(link,options.phoneMac,options.carPlayName,identity,previousBootstrap,
                experimentalIntegrityOnly=options.experiment,
                networkHandoff=networkHandoff,
                onNowPlayingCommand={command,fields->
                    // 发布器在 AirSession 建好后才有；先暂存订阅，建立后由 AirSession 取走。
                    NowPlayingUpdate.pendingSubscription=if(command==NowPlayingUpdate.START)fields else null
                    true
                })
        }
        val sourceAuthentication=requireNotNull(bootstrap.authentication)
        val sourceBinding=SecurityBinding(sourceAuthentication.generation,sessionId,sourceAuthentication.epoch)
        val sourceIdentification=bootstrap.identification
        resources.own(AutoCloseable{sourceIdentification?.invalidate()})
        val sourceContextCurrent=AtomicBoolean(true)
        resources.own(link.bindEpoch(sourceBinding.epoch){
            // 凭证已经收下时，车机重发 Detection 只是收尾方式，不是 stale 上下文。
            if(!credentialsReady.get()){
                sourceContextCurrent.set(false)
                sourceIdentification?.invalidate()
                host.failure(SecurityFailure(SecurityDiagnostic("iAP2","source-link-restart",SecurityReason.STALE_CONTEXT)))
            }
        })
        if(!sourceContextCurrent.get())securityFailure(SecurityReason.STALE_CONTEXT,phase="source-link-restart")
        networkHandoff.acceptBootstrap(bootstrap)
        onBootstrap(bootstrap)
        val handoff=HandoffSupervisor()
        val sourceRef=HandoffSupervisor.ResourceRef(sessionId+"-bt",1,link.linkEpoch)
        val targetRef=HandoffSupervisor.ResourceRef(sessionId+"-airplay",1)
        val leaseRef=HandoffSupervisor.ResourceRef(sessionId+"-network",1)
        // A session-local correlation token follows the chosen endpoint pipeline. Pair/AA still
        // verifies the actual peer; this token is not a claimed authenticated device identity.
        val peerCandidate=sessionId
        handoff.arm(sourceRef,targetRef,peerCandidate,leaseRef,System.nanoTime()+150_000_000_000L,
            requiresTargetIap=false)
        val cutoverTimer=deadlines.schedule({
            val snapshot=handoff.checkDeadline()
            if(snapshot.state==HandoffSupervisor.State.HANDOFF_FAILED)
                host.failure(TransportException(TransportErrorCategory.HANDOFF_FAILED,TransportErrorCategory.HANDOFF_FAILED.name))
        },150000,TimeUnit.MILLISECONDS)
        resources.own(AutoCloseable{cutoverTimer.cancel(false)})
        sourceFailure.set{error->
            val detached=handoff.snapshot.sourceDetached
            val eof=bluetoothPeerClosed(error)
            // 凭证在手时，握手后的非 EOF 链路终止（看门狗、NO_SYN）也只是蓝牙收尾。
            if(detached||eof||(credentialsReady.get()&&error is java.io.IOException)){
                val snapshot=handoff.sourceDetach(sourceRef)
                // 身份还要留给后面的热点和 AirPlay 校验。这里作废的话，蓝牙一断交接就退休。
                if(snapshot.state==HandoffSupervisor.State.HANDOFF_FAILED){
                    sourceIdentification?.invalidate()
                    host.failure(error)
                }else {
                    if(!detached)host.log("Bluetooth source detached during explicit handoff; target readiness remains independent")
                    resources.release(link);resources.release(bt)
                }
            }else host.failure(error)
        }
        // 先装好交接后的 failure 分流，再宣告凭证就绪，避免中间那一瞬走回旧 handler。
        credentialsReady.set(true)
        pendingSourceEof.getAndSet(null)?.let{sourceFailure.get().invoke(it)}
        scope.launch {
            fun checkSourceLink(){
                check(sourceContextCurrent.get()&&link.generation==sourceBinding.generation&&
                    link.linkEpoch==sourceBinding.epoch){"Post-bootstrap iAP link changed"}
                // poll owns terminal errors and preserves their cause (notably EOF,
                // which the handoff supervisor may classify as source detachment).
            }
            try{while(isActive){
                if(handoff.snapshot.sourceDetached)break
                checkSourceLink()
                val command=link.poll()
                if(handoff.snapshot.sourceDetached)break
                checkSourceLink()
                command?.let{
                    val fields=it.fields
                    when{
                        sourceIdentification?.handle(it,link.generation,link.linkEpoch){message->host.log(message)}==true->Unit
                        networkHandoff.handle(it,link.generation,link.linkEpoch)->Unit
                        fields!=null->host.log("Unsupported post-bootstrap Bluetooth iAP command "+it.id.toString(16))
                        // lingo 载荷不再是异常，也不丢弃：记录 id 与解析出的 lingo/command 后继续。
                        else->{val lingo=(it as IapCommand.Lingo).message;host.log("Post-bootstrap iAP lingo message id="+it.id.toString(16)+" lingo="+lingo.lingo+" command="+lingo.command.toString(16)+"; answer not implemented, recorded and continuing")}
                    }
                }
            }}
            catch(t:Exception){if(isActive&&!sessionEnded())sourceFailure.get().invoke(t)}
        }
        val networkReady=prepareNetwork(bootstrap,networkHandoff){configuration->
            phase.run(Stage.WIFI,45000){openNetwork(configuration,host::failure)}
        }
        val network=networkReady.network
        bootstrap=networkReady.bootstrap
        onBootstrap(bootstrap)
        val air=openSession(network,bootstrap,sourceBinding,localFps,
            {sourceContextCurrent.get()&&!sessionEnded()&&!failed.isCompleted&&
                link.linkEpoch==sourceBinding.epoch&&link.generation==sourceBinding.generation},
            controlPublication=networkReady.publication)
        return CarPlayUp(air){
            handoff.targetAttach(targetRef,peerCandidate)
            check(handoff.airplayReady(targetRef).committed)
            cutoverTimer.cancel(false)
        }
    }
}
