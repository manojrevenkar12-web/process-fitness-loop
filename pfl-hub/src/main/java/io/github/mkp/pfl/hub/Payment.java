package io.github.mkp.pfl.hub;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A payment as the hub holds it.
 *
 * @param uetr unique end-to-end transaction reference
 * @param hubReference the hub's own reference
 * @param amount amount
 * @param currency ISO 4217 code
 * @param valueDate settlement date
 * @param status ST settled, PD pending, RJ rejected, RT returned
 */
public record Payment(String uetr, String hubReference, BigDecimal amount, String currency, LocalDate valueDate, String status) {}
