package com.ukkera.pos.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(tableName = "protected_local_auth_configuration", primaryKeys = ["scope", "actorId"],
    indices = [Index(value = ["scope", "loginKey"], unique = true)],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])])
internal data class ProtectedLocalAuthConfigurationRow(
    val scope: String, val actorId: String, val loginKey: String,
    val schemaVersion: Int, val formatVersion: Int, val algorithm: String, val revision: Long,
    val keyId: String, val nonce: ByteArray, val ciphertextAndTag: ByteArray,
) { override fun toString(): String = "ProtectedLocalAuthConfigurationRow([REDACTED])" }

@Entity(tableName = "protected_local_refresh_issuance", primaryKeys = ["scope", "sessionId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])])
internal data class ProtectedLocalRefreshIssuanceRow(
    val scope: String, val sessionId: String,
    val schemaVersion: Int, val formatVersion: Int, val algorithm: String, val revision: Long,
    val keyId: String, val nonce: ByteArray, val ciphertextAndTag: ByteArray,
) { override fun toString(): String = "ProtectedLocalRefreshIssuanceRow([REDACTED])" }

@Entity(tableName = "protected_local_auth_event", primaryKeys = ["scope", "eventId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])])
internal data class ProtectedLocalAuthEventRow(
    val scope: String, val eventId: String,
    val schemaVersion: Int, val formatVersion: Int, val algorithm: String, val revision: Long,
    val keyId: String, val nonce: ByteArray, val ciphertextAndTag: ByteArray,
) { override fun toString(): String = "ProtectedLocalAuthEventRow([REDACTED])" }

@Entity(tableName = "protected_local_session_archive", primaryKeys = ["scope", "sessionId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])])
internal data class ProtectedLocalSessionArchiveRow(
    val scope: String, val sessionId: String,
    val schemaVersion: Int, val formatVersion: Int, val algorithm: String, val revision: Long,
    val keyId: String, val nonce: ByteArray, val ciphertextAndTag: ByteArray,
) { override fun toString(): String = "ProtectedLocalSessionArchiveRow([REDACTED])" }

/** Fixed-size locator projection. Invalid metadata is reported, not hidden as a missing account. */
internal data class LocalLoginLocator(val actorId: String, val storageValid: Boolean)
