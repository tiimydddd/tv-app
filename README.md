# Cozy TV 桌面

客厅场景桌面:每个物件对应一个 App。遥控器方向键移动,确认键打开。

## 自己的图和动画(可选,不用重新编译)
把文件放进盒子的这个目录(用 adb push):
/sdcard/Android/data/com.example.cozytv/files/

- scene.jpg          客厅主图(16:9)
- clips/<包名>.mp4   点选后先播放的动画,例如 clips/com.netflix.ninja.mp4
- config.json        自订物件位置,例如:

{"spots":[
 {"pkg":"com.netflix.ninja","name":"Netflix","hint":"抓一把爆米花","x":0.52,"y":0.70,"w":0.09,"h":0.18},
 {"pkg":"ALL","name":"所有应用","hint":"翻翻书架","x":0.865,"y":0.49,"w":0.13,"h":0.54}
]}

x、y 是物件中心,w、h 是大小,都是占整个画面的比例(0~1)。
pkg 写 ALL 表示"所有应用"列表。
