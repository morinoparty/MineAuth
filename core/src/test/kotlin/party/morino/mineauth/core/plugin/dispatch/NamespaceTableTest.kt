package party.morino.mineauth.core.plugin.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.PathSegment
import kotlin.reflect.typeOf

/**
 * NamespaceTableの候補索引（セグメント数バケット・具体性順）の検証
 */
class NamespaceTableTest {

    private class Dummy {
        fun handle(): String = "ok"
    }

    private fun endpoint(path: String, vararg segments: PathSegment, method: HttpMethodType = HttpMethodType.GET) =
        EndpointMetadata(
            method = Dummy::handle,
            handlerInstance = Dummy(),
            path = path,
            pathSegments = segments.toList(),
            httpMethod = method,
            access = EndpointAccess.Public(null),
            parameters = emptyList(),
            isSuspending = false,
            responseType = typeOf<String>(),
            returnsEither = false,
            responseResolvableByCore = true,
            returnsResponse = false
        )

    @Test
    @DisplayName("Candidates are bucketed by segment count and ordered by specificity")
    fun candidatesAreBucketedAndOrdered() {
        // 登録順はあえて「パラメータ → リテラル」にして、並び替えが効いていることを確認する
        val byId = endpoint("/shops/{id}", PathSegment.Literal("shops"), PathSegment.Param("id"))
        val mine = endpoint("/shops/mine", PathSegment.Literal("shops"), PathSegment.Literal("mine"))
        val list = endpoint("/shops", PathSegment.Literal("shops"))
        val table = NamespaceTable("Sample", "/api/v1/plugins/sample", listOf(byId, mine, list))

        assertEquals(listOf(mine, byId), table.candidates(2))
        assertEquals(listOf(list), table.candidates(1))
        assertTrue(table.candidates(3).isEmpty())
    }

    @Test
    @DisplayName("Equally specific routes keep registration order")
    fun tieKeepsRegistrationOrder() {
        // 同じ具体性（どちらも {x}/literal）なら登録順が保たれる（安定ソート）
        val first = endpoint("/{a}/one", PathSegment.Param("a"), PathSegment.Literal("one"))
        val second = endpoint("/{b}/two", PathSegment.Param("b"), PathSegment.Literal("two"))
        val table = NamespaceTable("Sample", "/api/v1/plugins/sample", listOf(first, second))

        assertEquals(listOf(first, second), table.candidates(2))
    }
}
