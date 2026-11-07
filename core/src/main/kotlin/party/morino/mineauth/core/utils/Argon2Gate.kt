package party.morino.mineauth.core.utils

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * リクエストの処理中に実行するArgon2の同時実行数を制限するゲート
 *
 * Argon2idは1回の検証で数十MiBのメモリを確保し、MinecraftサーバーとJVMのヒープを共有している。
 * Jettyのスレッドプールはリクエスト数に応じて増えるので、同時に届いた大量の検証がそのまま並列に走るとOutOfMemoryErrorでサーバーごと落ちる。
 * プロセス全体で1つのゲートを共有し、許可を得た検証だけを実行することで、同時に使うメモリの上限を固定する。
 * 流量制限の方針ではなくメモリの保護なので、rateLimit.enabledがfalseでも常に適用する
 *
 * 1つの接続元が待ち行列を埋めると、他の接続元の検証が全て待機時間切れになってしまう。
 * そのため接続元ごとに、待機中と実行中を合わせた検証の数を[maxInFlightPerSource]までに制限し、
 * 全体の許可の待ち行列には接続元ごとにその数までしか並べないようにする
 *
 * 待機はコルーチンのSemaphoreで行うので、許可を待つ間にJettyのスレッドを占有しない
 *
 * @param permits 同時に実行できる検証の数。1以上
 * @param acquireTimeout 接続元の枠と全体の許可を合わせて待つ時間の上限。過ぎた場合はハッシュを計算せずに諦める
 * @param maxInFlightPerSource 1つの接続元が同時に待機・実行できる検証の数。1以上
 */
class Argon2Gate(
    permits: Int = DEFAULT_PERMITS,
    private val acquireTimeout: Duration = ACQUIRE_TIMEOUT,
    private val maxInFlightPerSource: Int = MAX_IN_FLIGHT_PER_SOURCE
) {
    init {
        require(permits > 0) { "permits must be positive: $permits" }
        require(maxInFlightPerSource > 0) { "maxInFlightPerSource must be positive: $maxInFlightPerSource" }
    }

    private val semaphore = Semaphore(permits)

    /**
     * 1つの接続元の検証の枠
     * usersはロックの中でのみ読み書きするので、varのままでよい
     *
     * @property slots 接続元が同時に待機・実行できる検証の枠
     */
    private class SourceSlots(val slots: Semaphore) {
        // この接続元から来て、まだwithPermitを抜けていない検証の数（枠を待っているものも含む）
        var users: Int = 0
    }

    // 処理中の検証がある接続元の枠（このマップ自体をロックにも使う）
    // 検証が1つも残っていない接続元は削除するので、件数は処理中のリクエスト数を超えない
    private val sources = HashMap<ClientIpKey, SourceSlots>()

    /**
     * 待機中または実行中の検証がある接続元の数
     * 接続元の記録が残り続けず、メモリ使用量が処理中のリクエスト数で抑えられていることの確認に使う
     */
    internal val trackedSourceCount: Int
        get() = synchronized(sources) { sources.size }

    /**
     * 接続元の枠と全体の許可を得てから、ハッシュの計算を実行する
     *
     * 許可を持ったままDBのI/Oを待つと他の検証を不要に止めてしまうので、
     * ブロックには中断しない計算（ハッシュの計算と照合）だけを渡すこと
     *
     * @param source 検証を要求した接続元（流量制限と同じクライアントのキー）
     * @param hashing 許可を得ている間に実行するハッシュの計算
     * @return 計算結果。待機時間内に接続元の枠か全体の許可を得られなかった場合はLeft
     */
    suspend fun <T> withPermit(source: ClientIpKey, hashing: () -> T): Either<Argon2GateBusy, T> {
        val sourceSlots = enter(source)
        // タイムアウトはブロックの完了直前にも起こりうるので、戻り値ではなくフラグで取得できたかを判定する
        // （取得直後にタイムアウトするとwithTimeoutOrNullはnullを返すが、許可は保持したままになる）
        var sourceSlotAcquired = false
        var permitAcquired = false
        try {
            // 段階ごとに待つと最大で2倍待たせてしまうので、接続元の枠と全体の許可を1つの待機時間の中で取得する
            // 接続元の枠を先に取ることで、全体の許可の待ち行列に並ぶのは接続元ごとに枠の数までになる
            withTimeoutOrNull(acquireTimeout) {
                sourceSlots.slots.acquire()
                sourceSlotAcquired = true
                semaphore.acquire()
                permitAcquired = true
            }
            if (!permitAcquired) return Argon2GateBusy.left()
            return hashing().right()
        } finally {
            // 呼び出し元のキャンセルや例外で抜けた場合も、取得済みの許可と枠は必ず返す
            if (permitAcquired) semaphore.release()
            if (sourceSlotAcquired) sourceSlots.slots.release()
            leave(source, sourceSlots)
        }
    }

    /**
     * 接続元の枠を取得し、利用中として数える
     * 数えている間はleaveで削除されないので、同じ接続元の検証は必ず同じ枠を共有する
     *
     * @param source 検証を要求した接続元
     * @return 接続元の枠
     */
    private fun enter(source: ClientIpKey): SourceSlots = synchronized(sources) {
        val sourceSlots = sources.getOrPut(source) { SourceSlots(Semaphore(maxInFlightPerSource)) }
        sourceSlots.users += 1
        sourceSlots
    }

    /**
     * 利用中の数を戻し、誰も使っていない接続元の枠を削除する
     *
     * @param source 検証を要求した接続元
     * @param sourceSlots enterで取得した接続元の枠
     */
    private fun leave(source: ClientIpKey, sourceSlots: SourceSlots): Unit = synchronized(sources) {
        sourceSlots.users -= 1
        if (sourceSlots.users == 0) sources.remove(source)
    }

    companion object {
        /**
         * 既定の同時実行数
         * CPUコア数の半分をハッシュに充て、Minecraftサーバーのメインスレッドなどに残りを残す。
         * 上限の4は、1回あたり数十MiBのメモリを使う検証がヒープを圧迫しない数
         */
        val DEFAULT_PERMITS: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

        // 許可を待つ時間の上限（これより長く待たせると、利用者には応答しないサーバーに見える）
        val ACQUIRE_TIMEOUT: Duration = 2.seconds

        // 1つの接続元が同時に待機・実行できる検証の数
        // 1つの接続元が全体の待ち行列を埋めて、他の接続元の検証を待機時間切れにさせないための上限
        const val MAX_IN_FLIGHT_PER_SOURCE: Int = 2
    }
}
