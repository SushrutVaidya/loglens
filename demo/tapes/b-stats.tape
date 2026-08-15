Output out/b-stats.gif
Set Shell zsh
Set FontSize 16
Set Width 1400
Set Height 700
Set Padding 18
Set Theme "Dracula"
Set TypingSpeed 45ms

Hide
Type "export PATH=$PWD/bin:$PATH; clear" Enter
Show

Type "# 7 errors. how many actual problems?" Sleep 1s Enter
Sleep 400ms
Type "loglens --color --stats --quiet incident.log" Enter
Sleep 6s
