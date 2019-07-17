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
    //def message = "[${env.BUILD_URL}] - ${msg}"
    def message = "[${env.JOB_NAME} #${env.BUILD_NUMBER}] - ${msg}"
    slackSend color: color, message: message
}
