package com.baseball.score.repository;

import com.baseball.score.entity.OtpCode;
import com.baseball.score.enums.OtpPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {

    /** 取得該 email 最新一筆尚未使用的驗證碼 */
    Optional<OtpCode> findFirstByEmailIgnoreCaseAndPurposeAndConsumedAtIsNullOrderByIdDesc(String email, OtpPurpose purpose);

    Optional<OtpCode> findFirstByEmailIgnoreCaseOrderByIdDesc(String email);

    @Modifying
    @Query("delete from OtpCode o where o.expiresAt < :time")
    int deleteExpired(@Param("time") LocalDateTime time);

    @Modifying
    @Query("update OtpCode o set o.consumedAt = :now where o.email = :email and o.consumedAt is null")
    int consumeAll(@Param("email") String email, @Param("now") LocalDateTime now);
}
