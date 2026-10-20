package com.bohemian.odoo.pos.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "payment_attempt")
data class PaymentAttemptEntity(
    @PrimaryKey val billingRef: String,
    val orderUuid: String,
    val amountPaise: Long,
    val mode: String,
    val state: String,
    val eventId: String?,
    val detailsJson: String?,
    val statusChecks: Int,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** HARD RULE 7: a paid order waiting to reach Odoo. Deleted only after Odoo confirms. */
@Entity(tableName = "order_outbox")
data class OutboxEntity(
    @PrimaryKey val orderUuid: String,
    val payloadJson: String,
    val amountPaise: Long,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val nextAttemptAt: Long,
)

/**
 * The cart as it was when payment started. If the app dies mid-payment and the
 * Reconciler later confirms SUCCESS, this is what we record in Odoo.
 */
@Entity(tableName = "sale_snapshot")
data class SaleSnapshotEntity(
    @PrimaryKey val orderUuid: String,
    val json: String,
    val createdAt: Long,
)

@Dao
interface SaleSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: SaleSnapshotEntity)

    @Query("SELECT * FROM sale_snapshot WHERE orderUuid = :uuid")
    suspend fun get(uuid: String): SaleSnapshotEntity?

    @Query("DELETE FROM sale_snapshot WHERE orderUuid = :uuid")
    suspend fun delete(uuid: String)
}

@Dao
interface PaymentAttemptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: PaymentAttemptEntity)

    @Query("SELECT * FROM payment_attempt WHERE billingRef = :ref")
    suspend fun get(ref: String): PaymentAttemptEntity?

    @Query("SELECT * FROM payment_attempt WHERE orderUuid = :orderUuid ORDER BY createdAt")
    suspend fun forOrder(orderUuid: String): List<PaymentAttemptEntity>

    @Query("SELECT * FROM payment_attempt WHERE state IN ('STARTING','IN_FLIGHT','STATUS_CHECK','UNKNOWN','NEEDS_REVIEW') ORDER BY createdAt")
    suspend fun unresolved(): List<PaymentAttemptEntity>

    @Query("SELECT * FROM payment_attempt WHERE state IN ('STARTING','IN_FLIGHT','STATUS_CHECK','UNKNOWN','NEEDS_REVIEW') ORDER BY createdAt")
    fun observeUnresolved(): Flow<List<PaymentAttemptEntity>>

    @Query("UPDATE payment_attempt SET eventId = :eventId, state = CASE WHEN state = 'STARTING' THEN 'IN_FLIGHT' ELSE state END, updatedAt = :now WHERE billingRef = :ref")
    suspend fun setEventId(ref: String, eventId: String, now: Long)

    @Query("""UPDATE payment_attempt SET state = :state, updatedAt = :now,
              detailsJson = COALESCE(:detailsJson, detailsJson),
              note = COALESCE(:note, note),
              statusChecks = statusChecks + :inc
              WHERE billingRef = :ref""")
    suspend fun transition(ref: String, state: String, now: Long, detailsJson: String?, note: String?, inc: Int)
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: OutboxEntity)

    @Query("SELECT * FROM order_outbox WHERE nextAttemptAt <= :now ORDER BY createdAt")
    suspend fun due(now: Long): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM order_outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM order_outbox WHERE orderUuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("UPDATE order_outbox SET attempts = attempts + 1, lastError = :error, nextAttemptAt = :next WHERE orderUuid = :uuid")
    suspend fun markFailed(uuid: String, error: String, next: Long)
}

@Database(entities = [PaymentAttemptEntity::class, OutboxEntity::class, SaleSnapshotEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun attempts(): PaymentAttemptDao
    abstract fun outbox(): OutboxDao
    abstract fun snapshots(): SaleSnapshotDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bohemian-pos.db")
                // Payment data must never be silently dropped: no destructive migrations.
                .build()
    }
}
