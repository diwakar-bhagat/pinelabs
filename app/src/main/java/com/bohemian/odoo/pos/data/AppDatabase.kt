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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    /** `OdooErrorKind` of the last failure (null until one): how it is retried and what the bar says. */
    val errorKind: String? = null,
)

/** Outbox orders that failed the same way, for the bars: how many and Odoo's latest message. */
data class OutboxProblem(val kind: String, val count: Int, val lastError: String?)

/**
 * ZZ Cash points still to book for a synced sale (`pos.order.confirm_coupon_programs`).
 * Written together with the outbox row, deleted only after Odoo answers. Blocks session close (I9).
 */
@Entity(tableName = "loyalty_confirm")
data class LoyaltyConfirmEntity(
    @PrimaryKey val orderUuid: String,
    val couponDataJson: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val nextAttemptAt: Long,
    /** `OdooErrorKind` of the last failure. */
    val errorKind: String? = null,
)

@Dao
interface LoyaltyConfirmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: LoyaltyConfirmEntity)

    @Query("SELECT * FROM loyalty_confirm WHERE orderUuid = :uuid")
    suspend fun get(uuid: String): LoyaltyConfirmEntity?

    @Query("SELECT * FROM loyalty_confirm WHERE nextAttemptAt <= :now ORDER BY createdAt")
    suspend fun due(now: Long): List<LoyaltyConfirmEntity>

    @Query("SELECT COUNT(*) FROM loyalty_confirm")
    suspend fun count(): Int

    @Query("DELETE FROM loyalty_confirm WHERE orderUuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("UPDATE loyalty_confirm SET attempts = attempts + 1, lastError = :error, nextAttemptAt = :next, errorKind = :kind WHERE orderUuid = :uuid")
    suspend fun markFailed(uuid: String, error: String, next: Long, kind: String)
}

/**
 * Odoo's own answer to `account.tax.compute_all` for one line shape (F3, 0.7.0): kept on the till so
 * a cart Odoo has priced before can still be sold while Odoo is unreachable (I2: reused, never
 * reproduced). [taxStamp] is the newest tax / fiscal-position `write_date` it was priced under; any
 * change there empties the table.
 */
@Entity(tableName = "price_cache", primaryKeys = ["taxKey", "unitPaise", "qty", "fposId"])
data class PriceCacheEntity(
    /** The applied tax ids, sorted, comma-joined. */
    val taxKey: String,
    val unitPaise: Long,
    val qty: Int,
    /** 0 = no fiscal position. */
    val fposId: Int,
    val inclPaise: Long,
    val exclPaise: Long,
    val taxStamp: String,
    val savedAt: Long,
)

@Dao
interface PriceCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PriceCacheEntity)

    @Query("SELECT * FROM price_cache")
    suspend fun all(): List<PriceCacheEntity>

    @Query("DELETE FROM price_cache WHERE taxStamp != :stamp")
    suspend fun keepOnly(stamp: String)

    @Query("DELETE FROM price_cache")
    suspend fun clear()
}

/** A card Void on the terminal (Phase 4b). Never deleted: it is the audit trail of a reversal. */
@Entity(tableName = "void_attempt")
data class VoidAttemptEntity(
    @PrimaryKey val billingRef: String,
    val saleRef: String,
    val orderUuid: String,
    val amountPaise: Long,
    val state: String,
    val eventId: String?,
    val detailsJson: String?,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
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

    /** Carts saved before payment and not finalized yet (crash recovery, partly paid split bills). */
    @Query("SELECT * FROM sale_snapshot")
    suspend fun all(): List<SaleSnapshotEntity>
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

    /** Card sales the cashier may void (newest first). */
    @Query("SELECT * FROM payment_attempt WHERE mode = 'CARD' AND state IN ('SUCCESS','RESOLVED_PAID') AND createdAt >= :since ORDER BY createdAt DESC")
    suspend fun paidCardSince(since: Long): List<PaymentAttemptEntity>

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

    @Query("SELECT COUNT(*) FROM order_outbox")
    suspend fun count(): Int

    /** The order is still waiting to reach Odoo. */
    @Query("SELECT EXISTS(SELECT 1 FROM order_outbox WHERE orderUuid = :uuid)")
    suspend fun contains(uuid: String): Boolean

    @Query("DELETE FROM order_outbox WHERE orderUuid = :uuid")
    suspend fun delete(uuid: String)

    @Query("UPDATE order_outbox SET attempts = attempts + 1, lastError = :error, nextAttemptAt = :next, errorKind = :kind WHERE orderUuid = :uuid")
    suspend fun markFailed(uuid: String, error: String, next: Long, kind: String)

    /** A wait that is not the order's fault (no session, key rejected): no attempt is counted. */
    @Query("UPDATE order_outbox SET lastError = :error, nextAttemptAt = :next, errorKind = :kind WHERE orderUuid = :uuid")
    suspend fun markWaiting(uuid: String, error: String, next: Long, kind: String)

    /** "Retry now" (a supervisor), or a session just opened: these orders are sent on the next pass. */
    @Query("UPDATE order_outbox SET nextAttemptAt = 0 WHERE errorKind = :kind")
    suspend fun retryNow(kind: String)

    @Query("SELECT errorKind AS kind, COUNT(*) AS count, MAX(lastError) AS lastError FROM order_outbox WHERE errorKind IS NOT NULL GROUP BY errorKind")
    fun observeProblems(): Flow<List<OutboxProblem>>
}

/**
 * An open bill on this till (the queue of bills, 0.6.0): what the cashier put on it, as JSON
 * (`odoo/ParkedBill`). Never money: no price, no payment. Odoo sees the bill only once it is paid.
 */
@Entity(tableName = "parked_bill")
data class ParkedBillEntity(
    @PrimaryKey val orderUuid: String,
    val billJson: String,
    val createdAt: Long,
    /** When it was last the bill on screen; the newest is opened after a restart. */
    val activeAt: Long,
)

@Dao
interface ParkedBillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: ParkedBillEntity)

    @Query("SELECT * FROM parked_bill ORDER BY createdAt")
    suspend fun all(): List<ParkedBillEntity>

    @Query("DELETE FROM parked_bill WHERE orderUuid = :uuid")
    suspend fun delete(uuid: String)
}

@Dao
interface VoidAttemptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: VoidAttemptEntity)

    @Query("SELECT * FROM void_attempt WHERE billingRef = :ref")
    suspend fun get(ref: String): VoidAttemptEntity?

    @Query("SELECT * FROM void_attempt WHERE saleRef = :saleRef ORDER BY createdAt")
    suspend fun forSale(saleRef: String): List<VoidAttemptEntity>

    @Query("SELECT * FROM void_attempt WHERE state IN ('STARTING','IN_FLIGHT','NEEDS_REVIEW','VOIDED') ORDER BY createdAt")
    suspend fun open(): List<VoidAttemptEntity>

    @Query("SELECT * FROM void_attempt WHERE state IN ('STARTING','IN_FLIGHT','NEEDS_REVIEW','VOIDED') ORDER BY createdAt")
    fun observeOpen(): Flow<List<VoidAttemptEntity>>

    @Query("UPDATE void_attempt SET eventId = :eventId, state = CASE WHEN state = 'STARTING' THEN 'IN_FLIGHT' ELSE state END, updatedAt = :now WHERE billingRef = :ref")
    suspend fun setEventId(ref: String, eventId: String, now: Long)

    @Query("""UPDATE void_attempt SET state = :state, updatedAt = :now,
              detailsJson = COALESCE(:detailsJson, detailsJson),
              note = COALESCE(:note, note)
              WHERE billingRef = :ref""")
    suspend fun transition(ref: String, state: String, now: Long, detailsJson: String?, note: String?)
}

@Database(
    entities = [PaymentAttemptEntity::class, OutboxEntity::class, SaleSnapshotEntity::class, VoidAttemptEntity::class,
        LoyaltyConfirmEntity::class, ParkedBillEntity::class, PriceCacheEntity::class],
    version = 5, exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun attempts(): PaymentAttemptDao
    abstract fun outbox(): OutboxDao
    abstract fun snapshots(): SaleSnapshotDao
    abstract fun voids(): VoidAttemptDao
    abstract fun loyalty(): LoyaltyConfirmDao
    abstract fun parkedBills(): ParkedBillDao
    abstract fun priceCache(): PriceCacheDao

    companion object {
        /** v2 (Phase 4b): void_attempt table. SQL matches Room's generated createAllTables. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(VOID_ATTEMPT_DDL)
            }
        }

        /** v3: loyalty_confirm table (ZZ Cash booked after sync). SQL matches Room's createAllTables. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(LOYALTY_CONFIRM_DDL)
            }
        }

        /** v4 (0.6.0): parked_bill table, the queue of open bills. SQL matches Room's createAllTables. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(PARKED_BILL_DDL)
            }
        }

        /** v5 (0.7.0): error kinds on the outbox and loyalty rows; Odoo's saved prices for selling offline. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `order_outbox` ADD COLUMN `errorKind` TEXT")
                db.execSQL("ALTER TABLE `loyalty_confirm` ADD COLUMN `errorKind` TEXT")
                db.execSQL(PRICE_CACHE_DDL)
            }
        }

        internal const val PRICE_CACHE_DDL = "CREATE TABLE IF NOT EXISTS `price_cache` (" +
            "`taxKey` TEXT NOT NULL, `unitPaise` INTEGER NOT NULL, `qty` INTEGER NOT NULL, `fposId` INTEGER NOT NULL, " +
            "`inclPaise` INTEGER NOT NULL, `exclPaise` INTEGER NOT NULL, `taxStamp` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`taxKey`, `unitPaise`, `qty`, `fposId`))"

        internal const val PARKED_BILL_DDL = "CREATE TABLE IF NOT EXISTS `parked_bill` (" +
            "`orderUuid` TEXT NOT NULL, `billJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
            "`activeAt` INTEGER NOT NULL, PRIMARY KEY(`orderUuid`))"

        internal const val LOYALTY_CONFIRM_DDL = "CREATE TABLE IF NOT EXISTS `loyalty_confirm` (" +
            "`orderUuid` TEXT NOT NULL, `couponDataJson` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
            "`lastError` TEXT, `createdAt` INTEGER NOT NULL, `nextAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`orderUuid`))"

        internal const val VOID_ATTEMPT_DDL = "CREATE TABLE IF NOT EXISTS `void_attempt` (" +
            "`billingRef` TEXT NOT NULL, `saleRef` TEXT NOT NULL, `orderUuid` TEXT NOT NULL, " +
            "`amountPaise` INTEGER NOT NULL, `state` TEXT NOT NULL, `eventId` TEXT, `detailsJson` TEXT, " +
            "`note` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`billingRef`))"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "bohemian-pos.db")
                // Payment data must never be silently dropped: no destructive migrations.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
