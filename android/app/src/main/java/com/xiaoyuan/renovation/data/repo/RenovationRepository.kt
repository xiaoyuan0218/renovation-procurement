package com.xiaoyuan.renovation.data.repo

import com.xiaoyuan.renovation.data.model.AuthStateDto
import com.xiaoyuan.renovation.data.model.BatchDeleteInDto
import com.xiaoyuan.renovation.data.model.BatchDeleteResultDto
import com.xiaoyuan.renovation.data.model.CategoryDto
import com.xiaoyuan.renovation.data.model.CellSaveResultDto
import com.xiaoyuan.renovation.data.model.CredentialsInDto
import com.xiaoyuan.renovation.data.model.ExpenseDto
import com.xiaoyuan.renovation.data.model.ExpenseInDto
import com.xiaoyuan.renovation.data.model.ImportReportDto
import com.xiaoyuan.renovation.data.model.ItemDto
import com.xiaoyuan.renovation.data.model.ItemInDto
import com.xiaoyuan.renovation.data.model.ItemListDto
import com.xiaoyuan.renovation.data.model.ListInDto
import com.xiaoyuan.renovation.data.model.LoginResultDto
import com.xiaoyuan.renovation.data.model.MatrixCellInDto
import com.xiaoyuan.renovation.data.model.MatrixDto
import com.xiaoyuan.renovation.data.model.NameInDto
import com.xiaoyuan.renovation.data.model.OkDto
import com.xiaoyuan.renovation.data.model.RecordInDto
import com.xiaoyuan.renovation.data.model.RecordPatchDto
import com.xiaoyuan.renovation.data.model.PurgeResultDto
import com.xiaoyuan.renovation.data.model.RoomDto
import com.xiaoyuan.renovation.data.model.TrashItemDto
import com.xiaoyuan.renovation.data.model.SummaryDto
import com.xiaoyuan.renovation.data.remote.ApiClientFactory
import com.xiaoyuan.renovation.data.remote.ApiService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.IOException

/**
 * 全部后端交互的唯一出口，返回值统一是 [ApiResult]。
 *
 * 任何写操作成功后都会回调 [onDataChanged]，界面据此刷新 ——
 * 这样"在清单页改了一笔，回到总览页数字就变了"，不需要页面之间互相通知。
 *
 * 任何一个请求返回 401 都会回调 [onUnauthorized]（token 过期、或在别处改了密码），
 * 由容器负责清掉本地会话、把界面切回登录页。
 */
class RenovationRepository(
    private val api: ApiService,
    private val transferApi: ApiService,
    private val onDataChanged: () -> Unit = {},
    private val onUnauthorized: () -> Unit = {},
) {

    /* ---------- 登录 ---------- */

    /** 免登录：查询是否已创建账号、当前 token 是否仍有效。 */
    suspend fun authState(): ApiResult<AuthStateDto> = call { api.authState() }

    // 下面三个登录相关调用直接用 apiCall 而不是 call：
    // 密码错会返回 401，那是正常的失败结果，不该被当成"会话失效"踢回登录页。

    suspend fun login(username: String, password: String): ApiResult<LoginResultDto> =
        apiCall { api.login(CredentialsInDto(username, password)) }

    suspend fun setup(username: String, password: String): ApiResult<LoginResultDto> =
        apiCall { api.setup(CredentialsInDto(username, password)) }

    suspend fun logout(): ApiResult<OkDto> = call { api.logout() }

    /* ---------- 清单 ---------- */

    suspend fun lists(): ApiResult<List<ItemListDto>> = call { api.lists() }

    /**
     * 新建清单。[copyFrom] 传另一份清单的 id 时，只把它的分组与分类结构照抄过来
     * （不带物料），不传就是一张空白清单。
     */
    suspend fun createList(name: String, note: String = "", copyFrom: Int? = null): ApiResult<ItemListDto> =
        mutate { api.createList(ListInDto(name, note, sort = 0, copyFrom = copyFrom)) }

    suspend fun renameList(id: Int, name: String, note: String = "", sort: Int = 0): ApiResult<ItemListDto> =
        mutate { api.updateList(id, ListInDto(name, note, sort)) }

    /** 删清单会连它里面的物料、分组、分类一起删掉（服务端级联），最后一份不允许删。 */
    suspend fun deleteList(id: Int): ApiResult<OkDto> = mutate { api.deleteList(id) }

    /* ---------- 额外费用（运费/安装费）----------
     *
     * 这类钱独立于物料：不参与原价/日常价的三段拆分，只在总览单独汇总。
     */

    suspend fun expenses(): ApiResult<List<ExpenseDto>> = call { api.expenses() }

    suspend fun createExpense(body: ExpenseInDto): ApiResult<ExpenseDto> =
        mutate { api.createExpense(body) }

    suspend fun updateExpense(id: Int, body: ExpenseInDto): ApiResult<ExpenseDto> =
        mutate { api.updateExpense(id, body) }

    suspend fun deleteExpense(id: Int): ApiResult<OkDto> = mutate { api.deleteExpense(id) }

    /* ---------- 回收站 ---------- */

    suspend fun trash(): ApiResult<List<TrashItemDto>> = call { api.trash() }

    suspend fun restoreItem(id: Int): ApiResult<TrashItemDto> = mutate { api.restoreItem(id) }

    /** 彻底删除：连它的分配与采购记录一起，不可恢复。 */
    suspend fun purgeItem(id: Int): ApiResult<OkDto> = mutate { api.purgeItem(id) }

    suspend fun purgeTrash(): ApiResult<PurgeResultDto> = mutate { api.purgeTrash() }

    /* ---------- 总览 ---------- */

    suspend fun summary(): ApiResult<SummaryDto> = call { api.summary() }

    /* ---------- 物料 ---------- */

    suspend fun items(): ApiResult<List<ItemDto>> = call { api.items() }

    suspend fun item(id: Int): ApiResult<ItemDto> = call { api.item(id) }

    suspend fun createItem(body: ItemInDto): ApiResult<ItemDto> = mutate { api.createItem(body) }

    suspend fun updateItem(id: Int, body: ItemInDto): ApiResult<ItemDto> = mutate { api.updateItem(id, body) }

    suspend fun deleteItem(id: Int): ApiResult<OkDto> = mutate { api.deleteItem(id) }

    suspend fun batchDeleteItems(ids: List<Int>): ApiResult<BatchDeleteResultDto> =
        mutate { api.batchDeleteItems(BatchDeleteInDto(ids)) }

    /* ---------- 采购记录 ---------- */

    suspend fun addRecord(itemId: Int, body: RecordInDto): ApiResult<ItemDto> =
        mutate { api.addRecord(itemId, body) }

    suspend fun clearRecords(itemId: Int): ApiResult<ItemDto> = mutate { api.clearRecords(itemId) }

    suspend fun updateRecord(recordId: Int, body: RecordPatchDto): ApiResult<ItemDto> =
        mutate { api.updateRecord(recordId, body) }

    suspend fun deleteRecord(recordId: Int): ApiResult<ItemDto> = mutate { api.deleteRecord(recordId) }

    /* ---------- 分组 ---------- */

    suspend fun rooms(): ApiResult<List<RoomDto>> = call { api.rooms() }

    suspend fun createRoom(name: String): ApiResult<RoomDto> = mutate { api.createRoom(NameInDto(name)) }

    suspend fun renameRoom(id: Int, name: String): ApiResult<RoomDto> =
        mutate { api.updateRoom(id, NameInDto(name)) }

    suspend fun deleteRoom(id: Int): ApiResult<OkDto> = mutate { api.deleteRoom(id) }

    /* ---------- 分类 ---------- */

    suspend fun categories(): ApiResult<List<CategoryDto>> = call { api.categories() }

    suspend fun createCategory(name: String): ApiResult<CategoryDto> =
        mutate { api.createCategory(NameInDto(name)) }

    suspend fun renameCategory(id: Int, name: String): ApiResult<CategoryDto> =
        mutate { api.updateCategory(id, NameInDto(name)) }

    suspend fun deleteCategory(id: Int): ApiResult<OkDto> = mutate { api.deleteCategory(id) }

    /* ---------- 分配矩阵 ---------- */

    suspend fun matrix(): ApiResult<MatrixDto> = call { api.matrix() }

    suspend fun saveCell(
        itemId: Int,
        roomId: Int,
        qty: Double,
        priceOverride: Double?,
        note: String,
    ): ApiResult<CellSaveResultDto> = mutate {
        api.saveCell(MatrixCellInDto(itemId, roomId, qty, priceOverride, note))
    }

    /* ---------- 连接测试 ---------- */

    /** 用已保存的地址测试连通性（此时已登录，能顺带报出数据规模）。 */
    suspend fun testConnection(): ApiResult<SummaryDto> = summary()

    /**
     * 用用户刚输入、还没保存的地址测试连通性 ——
     * 引导页里临时建一个客户端，避免"先存再测、测不通还得改回来"。
     *
     * 这里必须打免登录接口：测试发生在登录之前，还没有 token。
     * 改打 /api/auth/state 还顺带告诉调用方后端有没有账号，下一步该创建还是该登录。
     */
    suspend fun testConnection(address: String): ApiResult<AuthStateDto> = apiCall {
        ApiClientFactory.create({ address }).authState()
    }

    /* ---------- 导入导出 ---------- */

    suspend fun downloadExport(): ApiResult<DownloadedFile> = call {
        readFile(transferApi.export(), "采购清单.xlsx")
    }

    suspend fun downloadTemplate(): ApiResult<DownloadedFile> = call {
        readFile(transferApi.importTemplate(), "导入模板.xlsx")
    }

    suspend fun importExcel(bytes: ByteArray, fileName: String, mode: String): ApiResult<ImportReportDto> =
        mutate {
            val fileBody = bytes.toRequestBody(XLSX_MIME.toMediaType())
            val part = MultipartBody.Part.createFormData("file", fileName, fileBody)
            val modeBody = mode.toRequestBody("text/plain".toMediaType())
            transferApi.import(part, modeBody)
        }

    private fun readFile(response: retrofit2.Response<okhttp3.ResponseBody>, fallbackName: String): DownloadedFile {
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: throw IOException("服务器返回了空文件")
        val name = parseFileName(response.headers()["Content-Disposition"], fallbackName)
        return DownloadedFile(name, body.bytes())
    }

    /** 需要登录的调用：401 表示会话失效，通知容器清凭证。 */
    private suspend fun <T> call(block: suspend () -> T): ApiResult<T> =
        apiCall { block() }.onErr { if (it.code == 401) onUnauthorized() }

    /** 写操作：成功后通知界面刷新。 */
    private suspend fun <T> mutate(block: suspend () -> T): ApiResult<T> =
        call { block() }.onOk { onDataChanged() }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
