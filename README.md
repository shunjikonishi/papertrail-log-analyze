Papertrail LogAnalyzer
======================

Overview
--------
Analyzing heroku logs.(Required papertrail addon)

Install
-------
    git clone git@github.com:shunjikonishi/papertrail-log-analyze.git
    heroku create
    git push heroku master
    heroku config:add S3_ACCESSKEY=<your aws accessKey> 
    heroku config:add S3_SECRETKEY=<your aws secretKey> 
    heroku config:add PAPERTRAIL_ARCHIVE_APP1=<your S3 bucket>/papertrail/logs
    heroku config:add TIMEZONE=Asia/Tokyo

You can add multiple PAPERTRAIL_ARCHIVE_xxxx.

If you want to access restriction, you should add following configs.

    heroku config:add ALLOWED_IP=xxx.xxx.xxx.xxx,yyy.yyy.yyy.yyy,zzz.zzz.zzz.zzz/24
    heroku config:add BASIC_AUTHENTICATION=username:password

Live sample
-----------
http://flect-papertrail.herokuapp.com/
