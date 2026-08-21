package com.passosfisio.app

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Leitura de passos do Health Connect — a "central" de dados de saúde do
 * Android, onde relógios, pulseiras e anéis inteligentes (Mi Band, Galaxy
 * Watch, Amazfit, etc.) costumam sincronizar os dados deles. Isso é opcional
 * e só entra em ação se o Health Connect estiver instalado e o usuário
 * autorizar — caso contrário, o app continua funcionando normalmente só
 * com o sensor do próprio celular.
 */
object HealthConnectHelper {

    val PERMISSOES = setOf(
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    /** Health Connect só existe a partir do Android 8 (API 26). */
    fun disponivel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            false
        }
    }

    private fun cliente(context: Context): HealthConnectClient? {
        return if (disponivel(context)) {
            try {
                HealthConnectClient.getOrCreate(context)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    suspend fun temPermissao(context: Context): Boolean {
        val client = cliente(context) ?: return false
        return try {
            val concedidas = client.permissionController.getGrantedPermissions()
            concedidas.containsAll(PERMISSOES)
        } catch (e: Exception) {
            false
        }
    }

    /** Retorna os passos de hoje segundo o Health Connect, ou null se não der pra ler. */
    suspend fun passosHoje(context: Context): Int? {
        val client = cliente(context) ?: return null
        if (!temPermissao(context)) return null

        val hoje = LocalDate.now(ZoneId.systemDefault())
        val inicio = hoje.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val fim = Instant.now()

        return try {
            val resposta = client.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(inicio, fim)
                )
            )
            resposta[StepsRecord.COUNT_TOTAL]?.toInt()
        } catch (e: Exception) {
            null
        }
    }
}
