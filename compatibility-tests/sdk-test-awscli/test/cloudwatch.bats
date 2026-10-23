#!/usr/bin/env bats
# CloudWatch tests

setup() {
    load 'test_helper/common-setup'
    ALARM_NAME="bats-alarm-$(unique_name)"
}

teardown() {
    aws_cmd cloudwatch delete-alarms --alarm-names "$ALARM_NAME" >/dev/null 2>&1 || true
}

@test "CloudWatch: describe-alarms returns every alarm collection" {
    aws_cmd cloudwatch put-metric-alarm \
        --alarm-name "$ALARM_NAME" \
        --metric-name CPUUtilization \
        --namespace AWS/EC2 \
        --statistic Average \
        --period 300 \
        --threshold 70 \
        --comparison-operator GreaterThanThreshold \
        --evaluation-periods 1 >/dev/null

    run aws_cmd cloudwatch describe-alarms --alarm-names "$ALARM_NAME"
    assert_success
    [ "$(json_get "$output" '.MetricAlarms[0].AlarmName')" = "$ALARM_NAME" ]
    [ "$(json_get "$output" '.CompositeAlarms | type')" = "array" ]
}

@test "CloudWatch: describe-alarms text output has no None line" {
    aws_cmd cloudwatch put-metric-alarm \
        --alarm-name "$ALARM_NAME" \
        --metric-name CPUUtilization \
        --namespace AWS/EC2 \
        --statistic Average \
        --period 300 \
        --threshold 70 \
        --comparison-operator GreaterThanThreshold \
        --evaluation-periods 1 >/dev/null

    run aws --endpoint-url "$FLOCI_ENDPOINT" --region "$AWS_DEFAULT_REGION" \
        cloudwatch describe-alarms --alarm-names "$ALARM_NAME" --output text
    assert_success
    refute_line --regexp '^None'
    assert_line --index 0 --partial "METRICALARMS"
}
