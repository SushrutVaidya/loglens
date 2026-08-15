Output out/a-transformation.gif
Set Shell zsh
Set FontSize 15
Set Width 1500
Set Height 760
Set Padding 18
Set Theme "Dracula"
Set TypingSpeed 45ms

Hide
Type "export PATH=$PWD/bin:$PATH; clear" Enter
Show

Type "# structured logs: great for machines, unreadable for humans" Sleep 900ms Enter
Sleep 400ms
Type "head -4 incident.log" Enter
Sleep 3s
Type "clear" Enter
Sleep 300ms
Type "# same lines, through loglens" Sleep 900ms Enter
Sleep 300ms
Type "loglens --color incident.log" Enter
Sleep 5s
