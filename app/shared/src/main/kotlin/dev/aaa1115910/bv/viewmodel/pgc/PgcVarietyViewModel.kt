package dev.aaa1115910.bv.viewmodel.pgc

import dev.aaa1115910.biliapi.entity.pgc.PgcType
import dev.aaa1115910.biliapi.repositories.PgcRepository
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class PgcVarietyViewModel(
    override val pgcRepository: PgcRepository
) : PgcViewModel(
    pgcRepository = pgcRepository,
    pgcType = PgcType.Variety
)