package io.aatricks.novelscraper.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.aatricks.novelscraper.data.model.CustomSourceRecipe

@Dao
interface CustomSourceRecipeDao {
    @Query("SELECT * FROM custom_source_recipes WHERE id = :id")
    suspend fun getById(id: String): CustomSourceRecipe?

    @Query("SELECT * FROM custom_source_recipes WHERE baseNovelUrl = :baseNovelUrl LIMIT 1")
    suspend fun getByBaseNovelUrl(baseNovelUrl: String): CustomSourceRecipe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: CustomSourceRecipe)
}
