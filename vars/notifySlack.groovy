#!/usr/bin/env groovy

def call (String msg) {
    def color = ''
    if (msg.indexOf('SUCCESS') > -1) {
        color = 'good'
    } else if (msg.indexOf('ERROR') > - 1) {
        color = 'danger'
    } else {
        color = 'warning'
    }
    def message = "[${env.BUILD_URL}] - ${msg}"
    slackSend color: color, message: message
}
