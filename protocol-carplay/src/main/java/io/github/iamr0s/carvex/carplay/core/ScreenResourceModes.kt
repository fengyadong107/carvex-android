package io.github.iamr0s.carvex.carplay.core

/** 接收端声明里「nice to have」的 transferPriority 与「anytime」的各类 constraint 都取这个值。 */
private const val UNCONDITIONAL=100

/** Capability-driven subset for MainScreen(1) and MainAudio(2). Unknown constraints remain rejected. */
class ScreenResourceModes {
    data class State(val owner:Int=1,val permanent:Int=1,val borrowed:Boolean=false,
        val audioOwner:Int=2,val audioPermanent:Int=2,val audioBorrowed:Boolean=false)
    var state=State();private set
    fun audio(phone:Boolean){state=state.copy(audioOwner=if(phone)1 else 2,audioPermanent=if(phone)1 else 2,audioBorrowed=false)}
    fun requestedResource(params:Map<*,*>):Int? {
        val entry=((params["resources"] as? List<*>)?.singleOrNull() as? Map<*,*>)?:return null
        val value=entry["resourceID"] as? Number?:return null
        val number=value.toDouble()
        return value.toInt().takeIf{number.isFinite()&&number==it.toDouble()&&it in 1..2}
    }
    fun propose(params:Map<*,*>):State? {
        if(params.keys.any{it !in setOf("resources","appStates")})return null
        val apps=params["appStates"]
        if(apps!=null&&(apps !is List<*>||apps.isNotEmpty()))return null
        val requests=params["resources"] as? List<*>?:return null
        if(requests.size!=1)return null
        val r=requests.single() as? Map<*,*>?:return null
        // 接收端在 /info 的 modes 里用一组约束声明资源能力，回 changeModes 时会原样带上这几个字段。
        if(r.keys.any{it !in setOf("resourceID","transferType","transferPriority","takeConstraint","borrowConstraint","unborrowConstraint")})return null
        fun integer(v:Any?):Int? {val n=v as? Number?:return null;val d=n.toDouble();if(!d.isFinite()||d!=n.toInt().toDouble())return null;return n.toInt()}
        val resource=integer(r["resourceID"])?:return null;val transfer=integer(r["transferType"])?:return null
        // TAKE/GIVE 是无条件的所有权转移，约束值不改变结果；借用/归还要评估条件，只接受无条件那一档。
        // 实测车机取主音频时带的是 transferPriority/takeConstraint=500，拿 100 卡它会把整个请求拒掉。
        if(transfer>2&&listOf("transferPriority","takeConstraint","borrowConstraint","unborrowConstraint").any{r.containsKey(it)&&integer(r[it])!=UNCONDITIONAL})return null
        if(resource==1)return when(transfer){
            1->if(state.borrowed)null else state.copy(owner=2,permanent=2,borrowed=false)
            2->if(state.borrowed)null else state.copy(owner=1,permanent=1,borrowed=false)
            3->if(state.borrowed||state.owner!=1)null else state.copy(owner=2,borrowed=true)
            4->if(!state.borrowed)null else state.copy(owner=state.permanent,borrowed=false)
            else->null
        }
        if(resource==2)return when(transfer){
            1->if(state.audioBorrowed)null else state.copy(audioOwner=2,audioPermanent=2,audioBorrowed=false)
            2->if(state.audioBorrowed)null else state.copy(audioOwner=1,audioPermanent=1,audioBorrowed=false)
            3->if(state.audioBorrowed||state.audioOwner!=1)null else state.copy(audioOwner=2,audioBorrowed=true)
            4->if(!state.audioBorrowed)null else state.copy(audioOwner=state.audioPermanent,audioBorrowed=false)
            else->null
        }
        return null
    }
    fun commit(next:State){state=next}
    fun params(value:State=state):Map<String,Any?> = mapOf(
        "resources" to listOf(
            mapOf("resourceID" to 1,"entity" to value.owner,"permanentEntity" to value.permanent),
            mapOf("resourceID" to 2,"entity" to value.audioOwner,"permanentEntity" to value.audioPermanent)
        ),
        "appStates" to listOf(mapOf("appStateID" to 1,"entity" to 0,"speechMode" to -1),mapOf("appStateID" to 2,"entity" to 0),mapOf("appStateID" to 3,"entity" to 0))
    )
}

/** status-only 的拒绝没有 params。再发一条空的 modesChanged，接收端会回 422。 */
fun modesChangedNotification(reply:Map<*,*>):Map<String,Any?>? {
    val params=reply["params"] as? Map<*,*>?:return null
    return mapOf("type" to "modesChanged","params" to params)
}
