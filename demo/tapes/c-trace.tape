Output out/c-trace.gif
Set Shell zsh
Set FontSize 15
Set Width 1500
Set Height 620
Set Padding 18
Set Theme "Dracula"
Set TypingSpeed 45ms

Hide
Type "export PATH=$PWD/bin:$PATH; clear" Enter
Show

Type "# follow one request through the incident" Sleep 1s Enter
Sleep 400ms
Type "loglens --color --trace 9f2c-a1 incident.log" Enter
Sleep 5s
Type "# cache miss -> pool exhausted -> 500 -> 200 after 171 seconds" Sleep 1s Enter
Sleep 2500ms
