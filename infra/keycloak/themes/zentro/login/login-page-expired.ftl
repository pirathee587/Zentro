<#import "template.ftl" as layout>
<!doctype html>
<html lang="en" style="background: #120e0b;">
  <head>
    <meta charset="utf-8" />
    <meta http-equiv="refresh" content="0;url=${url.loginRestartFlowUrl}" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <script>window.location.replace("${url.loginRestartFlowUrl}");</script>
    <style>
      html, body {
        background: #120e0b !important;
        margin: 0;
        padding: 0;
        width: 100%;
        height: 100%;
        overflow: hidden;
      }
    </style>
    <title>Signing in...</title>
  </head>
  <body style="background: #120e0b !important;"></body>
</html>
