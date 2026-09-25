package us.wangxy.voicebook.screens.detail

import androidx.lifecycle.ViewModel
import us.wangxy.voicebook.data.MuseumObject
import us.wangxy.voicebook.data.MuseumRepository
import kotlinx.coroutines.flow.Flow

class DetailViewModel(private val museumRepository: MuseumRepository) : ViewModel() {
    fun getObject(objectId: Int): Flow<MuseumObject?> =
        museumRepository.getObjectById(objectId)
}
