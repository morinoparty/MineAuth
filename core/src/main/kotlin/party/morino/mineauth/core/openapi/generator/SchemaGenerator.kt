package party.morino.mineauth.core.openapi.generator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.openapi.model.Schema
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.memberProperties

/**
 * KTypeからOpenAPIスキーマを生成するジェネレーター
 * Kotlinのリフレクションを使用して型情報からJSONスキーマを構築する
 */
class SchemaGenerator : KoinComponent {

    /**
     * KTypeからOpenAPIスキーマを生成する
     *
     * @param type 変換対象の型
     * @return 生成されたスキーマ
     */
    fun generateSchema(type: KType): Schema {
        // 循環参照追跡用のセットを関数ローカルで作成（スレッドセーフ）
        return generateSchemaInternal(type, mutableSetOf())
    }

    /**
     * 内部用スキーマ生成（循環参照追跡付き）
     *
     * @param type 変換対象の型
     * @param processingTypes 処理中の型を追跡するセット（スレッドセーフのため関数ローカル）
     * @return 生成されたスキーマ
     */
    private fun generateSchemaInternal(type: KType, processingTypes: MutableSet<KClass<*>>): Schema {
        val classifier = type.classifier as? KClass<*>
            ?: return Schema(type = "object")

        val isNullable = type.isMarkedNullable

        val schema = when (classifier) {
            // プリミティブ型
            String::class -> Schema(type = "string")
            Int::class -> Schema(type = "integer", format = "int32")
            Long::class -> Schema(type = "integer", format = "int64")
            Short::class -> Schema(type = "integer", format = "int32")
            Byte::class -> Schema(type = "integer", format = "int32")
            Float::class -> Schema(type = "number", format = "float")
            Double::class -> Schema(type = "number", format = "double")
            Boolean::class -> Schema(type = "boolean")
            Char::class -> Schema(type = "string")

            // 特殊な型
            UUID::class -> Schema(type = "string", format = "uuid")

            // Bukkit型はUUIDとして表現
            Player::class, OfflinePlayer::class -> Schema(
                type = "string",
                format = "uuid",
                description = "Player UUID"
            )

            // コレクション型
            List::class, Set::class, Collection::class -> {
                val itemType = type.arguments.firstOrNull()?.type
                Schema(
                    type = "array",
                    items = itemType?.let { generateSchemaInternal(it, processingTypes) }
                        ?: Schema(type = "object")
                )
            }

            Array::class -> {
                val itemType = type.arguments.firstOrNull()?.type
                Schema(
                    type = "array",
                    items = itemType?.let { generateSchemaInternal(it, processingTypes) }
                        ?: Schema(type = "object")
                )
            }

            // Map型
            Map::class -> {
                Schema(
                    type = "object",
                    additionalProperties = true
                )
            }

            // 列挙型
            else -> if (classifier.java.isEnum) {
                generateEnumSchema(classifier)
            } else if (classifier.hasAnnotation<Serializable>()) {
                // @Serializable付きデータクラス
                generateObjectSchema(classifier, processingTypes)
            } else {
                // その他のオブジェクト型
                Schema(type = "object")
            }
        }

        // nullableの場合はnullable: trueを設定
        return if (isNullable) {
            schema.copy(nullable = true)
        } else {
            schema
        }
    }

    /**
     * 列挙型からスキーマを生成する
     * enumのname（定数名）を使用してシリアライズ値と一致させる
     */
    private fun generateEnumSchema(kClass: KClass<*>): Schema {
        val enumValues = kClass.java.enumConstants
            ?.map { (it as Enum<*>).name }
            ?: emptyList()

        return Schema(
            type = "string",
            enum = enumValues
        )
    }

    /**
     * @Serializable付きオブジェクト型からスキーマを生成する
     *
     * @param kClass 対象のクラス
     * @param processingTypes 循環参照追跡用のセット
     */
    private fun generateObjectSchema(kClass: KClass<*>, processingTypes: MutableSet<KClass<*>>): Schema {
        // 循環参照チェック
        if (kClass in processingTypes) {
            return Schema(type = "object", description = "Circular reference: ${kClass.simpleName}")
        }

        processingTypes.add(kClass)

        try {
            val properties = mutableMapOf<String, Schema>()
            val requiredProps = mutableListOf<String>()

            // データクラスのプロパティを反復処理
            for (prop in kClass.memberProperties) {
                // @SerialNameがあればその名前を使用、なければプロパティ名
                val serialName = prop.findAnnotation<SerialName>()?.value ?: prop.name
                val propSchema = generateSchemaInternal(prop.returnType, processingTypes)
                properties[serialName] = propSchema

                // null許容でないプロパティは必須
                if (!prop.returnType.isMarkedNullable) {
                    requiredProps.add(serialName)
                }
            }

            return Schema(
                type = "object",
                properties = properties.takeIf { it.isNotEmpty() },
                required = requiredProps.takeIf { it.isNotEmpty() }
            )
        } finally {
            processingTypes.remove(kClass)
        }
    }

    /**
     * 戻り値の型からレスポンススキーマを生成する
     * Unitの場合はnullを返す（レスポンスボディなし）
     */
    fun generateResponseSchema(returnType: KType): Schema? {
        val classifier = returnType.classifier as? KClass<*>
            ?: return null

        // Unitの場合はレスポンスボディなし
        if (classifier == Unit::class) {
            return null
        }

        return generateSchema(returnType)
    }
}
