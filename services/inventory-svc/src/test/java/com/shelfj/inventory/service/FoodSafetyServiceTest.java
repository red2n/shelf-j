package com.shelfj.inventory.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.shelfj.inventory.domain.FoodSafety.CheckType;
import com.shelfj.inventory.domain.FoodSafety.Kind;
import com.shelfj.inventory.domain.FoodSafety.Limits;
import com.shelfj.inventory.domain.FoodSafety.Result;
import com.shelfj.web.ApiException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FoodSafetyServiceTest {

  private static final CheckType CHILLED =
      new CheckType(
          UUID.randomUUID(),
          null,
          "CHILLED_STORAGE",
          "Chilled storage",
          Kind.TEMPERATURE,
          new Limits(null, new BigDecimal("8.00")),
          "C",
          null,
          true,
          true);

  private static final CheckType OPENING =
      new CheckType(
          UUID.randomUUID(),
          null,
          "OPENING_CHECKS",
          "Opening checks",
          Kind.PASS_FAIL,
          Limits.NONE,
          null,
          null,
          false,
          true);

  @Test
  void aTemperatureCheckIsJudgedFromTheReadingAndRefusesAVerdict() {
    Limits limits = CHILLED.limits();
    assertThat(
        FoodSafetyService.judge(Kind.TEMPERATURE, limits, new BigDecimal("9.5"), null),
        is(Result.FAIL));
    assertThat(
        code(() -> FoodSafetyService.judge(Kind.TEMPERATURE, limits, null, null)),
        is("FOOD_SAFETY_VALUE_REQUIRED"));
    // The client must never be able to declare a warm chiller a pass.
    assertThat(
        code(() -> FoodSafetyService.judge(Kind.TEMPERATURE, limits, new BigDecimal("9.5"), true)),
        is("FOOD_SAFETY_PASSED_NOT_ALLOWED"));
  }

  @Test
  void aPassFailCheckNeedsAVerdictAndNoReading() {
    assertThat(FoodSafetyService.judge(Kind.PASS_FAIL, Limits.NONE, null, false), is(Result.FAIL));
    assertThat(
        code(() -> FoodSafetyService.judge(Kind.PASS_FAIL, Limits.NONE, null, null)),
        is("FOOD_SAFETY_PASSED_REQUIRED"));
    assertThat(
        code(() -> FoodSafetyService.judge(Kind.PASS_FAIL, Limits.NONE, BigDecimal.ONE, true)),
        is("FOOD_SAFETY_VALUE_NOT_ALLOWED"));
  }

  @Test
  void aPointTakesItsTypesLimitsByDefaultAndMayOnlyTightenThem() {
    assertThat(
        FoodSafetyService.pointLimits(CHILLED, Limits.NONE).max(), is(new BigDecimal("8.00")));
    Limits withMin = FoodSafetyService.pointLimits(CHILLED, new Limits(BigDecimal.ZERO, null));
    assertThat(withMin.min().toPlainString(), is("0.00"));
    assertThat(withMin.max(), is(new BigDecimal("8.00")));

    ApiException laxer =
        assertThrows(
            ApiException.class,
            () -> FoodSafetyService.pointLimits(CHILLED, new Limits(null, new BigDecimal("10"))));
    assertThat(laxer.status(), is(422));
    assertThat(laxer.code(), is("FOOD_SAFETY_LIMIT_LAXER_THAN_TYPE"));
  }

  @Test
  void pointLimitsRefuseInvertedBoundsAndLimitsOnAPassFailCheck() {
    assertThat(
        code(
            () ->
                FoodSafetyService.pointLimits(
                    CHILLED, new Limits(new BigDecimal("6"), new BigDecimal("4")))),
        is("FOOD_SAFETY_LIMITS_INVERTED"));
    assertThat(
        code(() -> FoodSafetyService.pointLimits(OPENING, new Limits(null, new BigDecimal("1")))),
        is("FOOD_SAFETY_LIMITS_NOT_ALLOWED"));
  }

  @Test
  void aReadingIsJudgedAtThePrecisionItIsStoredAt() {
    // 8.004 would fail against 8.00 and then be stored by NUMERIC(6,2) as 8.00, a passing value.
    assertThat(
        code(() -> FoodSafetyService.celsius(new BigDecimal("8.004"), "value")),
        is("FOOD_SAFETY_TOO_PRECISE"));
    assertThat(
        FoodSafetyService.celsius(new BigDecimal("9.5"), "value").toPlainString(), is("9.50"));
    assertThat(
        FoodSafetyService.celsius(new BigDecimal("8.000"), "value").toPlainString(), is("8.00"));
    assertThat(
        code(() -> FoodSafetyService.celsius(new BigDecimal("-300"), "value")),
        is("FOOD_SAFETY_OUT_OF_RANGE"));
    assertThat(
        code(
            () ->
                FoodSafetyService.pointLimits(CHILLED, new Limits(null, new BigDecimal("4.125")))),
        is("FOOD_SAFETY_TOO_PRECISE"));
  }

  private static String code(Runnable r) {
    return assertThrows(ApiException.class, r::run).code();
  }
}
