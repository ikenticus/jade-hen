#!/usr/bin/env groovy

def call (String msg) {
    def icon = ''
    def color = ''
    if (msg.indexOf('SUCCESS') > -1) {
        icon = ':trophy:'
        color = 'good'
    } else if (msg.indexOf('ERROR') > - 1) {
        icon = ':bomb:'
        color = 'danger'
    } else if (msg.indexOf('INIT') > -1) {
        icon = ':rocket:'
        color = '#33FFFF'
    } else {
        icon = ':warning:'
        color = 'warning'
    }
    //def message = "[${env.BUILD_URL}] - ${msg}"
    def message = "${icon}[${env.JOB_NAME} #${env.BUILD_NUMBER}] - ${msg}"
    slackSend color: color, message: message
}
