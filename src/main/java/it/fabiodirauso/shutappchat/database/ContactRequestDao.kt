package it.fabiodirauso.shutappchat.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import it.fabiodirauso.shutappchat.model.ContactRequest
import it.fabiodirauso.shutappchat.model.ContactRequestStatus

@Dao
interface ContactRequestDao {
    
    @Query("SELECT * FROM contact_requests WHERE status = :status ORDER BY createdAt DESC")
    fun getRequestsByStatus(status: ContactRequestStatus): Flow<List<ContactRequest>>
    
    @Query("SELECT * FROM contact_requests WHERE toUserId = :userId AND status = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingRequestsForUser(userId: Long): Flow<List<ContactRequest>>
    
    @Query("SELECT COUNT(*) FROM contact_requests WHERE toUserId = :userId AND status = 'PENDING'")
    fun getPendingRequestCount(userId: Long): Flow<Int>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: ContactRequest)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequests(requests: List<ContactRequest>)
    
    @Update
    suspend fun updateRequest(request: ContactRequest)
    
    @Query("UPDATE contact_requests SET status = :status, updatedAt = :updatedAt WHERE id = :requestId")
    suspend fun updateRequestStatus(requestId: Long, status: ContactRequestStatus, updatedAt: java.util.Date)
    
    @Delete
    suspend fun deleteRequest(request: ContactRequest)
    
    @Query("DELETE FROM contact_requests WHERE id = :requestId")
    suspend fun deleteRequestById(requestId: Long)
    
    @Query("DELETE FROM contact_requests WHERE status = :status")
    suspend fun deleteRequestsByStatus(status: ContactRequestStatus)
}
