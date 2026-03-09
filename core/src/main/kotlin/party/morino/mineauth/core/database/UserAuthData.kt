package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.Table

object UserAuthData: Table() {
    val uuid = varchar("uuid", 36)
    val password = varchar("password", 255)
    override val primaryKey = PrimaryKey(uuid)
}