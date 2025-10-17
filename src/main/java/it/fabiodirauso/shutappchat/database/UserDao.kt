package it.fabiodirauso.shutappchat.database

import androidx.room.*
import it.fabiodirauso.shutappchat.model.User

@Dao
interface UserDao {

    @Query("SELECT * FROM users WHERE id = :userId")
    suspend fun getUserById(userId: String): User?
    
    @Query("SELECT * FROM users WHERE username = :username LIMIT 1")
    suspend fun getUserByUsername(username: String): User?
    
    @Query("SELECT id FROM users WHERE username = :username LIMIT 1")
    suspend fun getUserIdByUsername(username: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: User)

    @Update
    suspend fun updateUser(user: User)

    @Delete
    suspend fun deleteUser(user: User)

    @Query("SELECT * FROM users")
    suspend fun getAllUsers(): List<User>
    
    @Query("SELECT * FROM users WHERE username LIKE :query OR nickname LIKE :query")
    suspend fun searchUsers(query: String): List<User>
}
