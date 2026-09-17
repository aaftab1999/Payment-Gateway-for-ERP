package com.paymentgateway.settlement.application.service;

import com.paymentgateway.settlement.domain.payment.Payment;

public record ChargeResult(Payment payment, boolean replayed, int responseStatus) {
}
