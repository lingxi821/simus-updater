# **ManifestEditor**
This is a tool used to modify Android Manifest binary file.  
此工具用于修改 AndroidManifest 二进制文件（AXML）。比如，更改 manifest 文件中的 app 包名、版本号，更改或新增 app 入口 Application 的类名，更改或新增 debuggable 属性，增加 uses-permission 标签、meta-data 标签等。
同时，为了更方便使用，提供了直接修改 apk 包中的 manifest 文件，并对修改后的 apk 进行签名的功能。

比较常见的修改 AndroidManifest 二进制文件的工具，大致有 [apkeditor](https://github.com/8enet/apkeditor) 和 [AXMLEditor](https://github.com/fourbrother/AXMLEditor)。

但是，这些工具都有一个相同的问题：新增的属性无法被 Android 系统解析出来。
比如，在 application 标签下增加 `debuggable=true` 属性，安装后的 App 并不是 debuggable 的。

本工具并不存在此问题。当然，本工具可能存在其他一些问题，并未作充分测试。

此项目基于 [axml](https://github.com/Sable/axml)，并在其基础上做了二次封装和一些优化，使用起来更加方便。

This tool is used in project [Xpatch](https://github.com/WindySha/Xpatch)
#  **Jar包下载**
打开release页面下载，或[点击下载V1.0.2](https://github.com/WindySha/ManifestEditor/releases/download/v1.0.2/ManifestEditor-1.0.2.jar)

# **查看帮助文档**
```
$ java -jar ../ManifestEditor.jar -h
```
得到使用文档：
```
options:
 -aa,--applicationAttribute <application-attribute-name-value>
                             set the application attribute,  name and value shou
                             ld be separated by : , if name is in android namesp
                             ace, prefix "android-" should be set, multi option 
                             is supported
 -act,--activity <activity-config>
                             add/replace activity, format: activity-name[:exported]
                             , supports true/false or 1/0 for exported
                             , multi option is supported
 -an,--applicationName <new-application-name>
                             set the app entry application name
 -d,--debuggable <0 or 1>    set 1 to make the app debuggable = true, set 0 to m
                             ake the app debuggable = false
 -dmd,--deleteMetaDataList <delete-meta-data-name>
                             delete the meta data name, multi option is supporte
                             d
 -f,--force                  force overwrite
 -h,--help                   Print this help message
 -ma,--manifestAttribute <manifest-attribute-name-value>
                             set the app manifest attribute,  name and value sho
                             uld be separated by : , if name is in android names
                             pace, prefix "android-" should be set, multi option
                              is supported
 -md,--metaData <meta-data-name-value>add the meta data,  name and value should be separa
                             ted by :, multi option is supported
 -o,--output <output-file>   output modified xml or apk file, default is $source
                             _apk_dir/[file-name]-new.xml or [file-name]-new-uns
                             igned.apk
 -pkg,--packageName <new-package-name>set the android manifest package name
 -s,--signApk                use jarsigner to sign the output apk file
 -up,--usesPermission <uses-permission-name>
                             add the app uses permission name to the manifest fi
                             le, multi option is supported
 -vc,--versionCode <new-version-code>set the app version code
 -vn,--versionName <new-version-name>set the app version name
version: 1.0.2
```
# **修改 Manifest 文件**
### 1. 修改 Manifest 中 app 包名: `-pkg`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -pkg com.test.newpackage
```
在AndroidManifest.xml文件相同的目录下，会生成一个新的xml文件：AndroidManifest-new.xml

这个新的manifest文件中，package被改成了`com.test.newpackage`。
### 2. 新增 debuggable = true 的属性: `-d`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -d 1
```
新的 manifest 文件中，Application 标签下，增加了`android:debuggable = "true"`属性。
如果需要将 debuggable 改为 false，只需：
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -d 0
```
### 3. 修改 Manifest 文件中的 versionCode 和 versionName: `-vc` `-vn`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -vc 100 -vn 1.0.0
```
新的manifest文件中，versionCode被改成了`100`，versionName被改成了`1.0.0`。
### 4. 修改 Manifest 文件中的 applicationName: `-an`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -an com.test.new.MyApplication
```
新的manifest文件中，application标签下的name被改为：`android:name="com.test.new.MyApplication"`。
### 5. 新增 Manifest 文件中的 usesPermission 标签:`-up`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -up android.permission.READ_EXTERNAL_STORAGE -up android.permission.WRITE_EXTERNAL_STORAGE
```
新的manifest文件中，新增了读写sdcard两个权限标签。假如原Manifest文件中已经存在相关权限标签，则不会增加新的。
### 6. 增加或修改顶层的 manifest 标签下的其他属性:`-ma`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -ma android-compileSdkVersion:28 -ma android-compileSdkVersionCodename:9
```
新的 manifest 文件中，顶层的 manifest 签下新增或者修改的标签为：
```
<manifest
   ...
    android:compileSdkVersion="28"
    android:compileSdkVersionCodename="9">
```
对于非 android 命名空间下的属性，去掉命令中的 `android-` 即可，暂不支持其他命名空间下的属性的更改。比如：
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -ma platformBuildVersionCode:100
```
改动的属性是 platformBuildVersionCode：
```
<manifest
   ...
   platformBuildVersionCode="100">
```
### 7. 增加或修改 application 标签下的其他属性: `-aa`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -aa android-allowBackup:false
```
新的 manifest 文件中，application 标签下新增或者修改的标签为：
```
<application
   ...
   android:allowBackup="false"
    ...
>
```
对于非 android 命名空间下的属性，去掉命令中的 `android-` 即可，暂不支持其他命名空间下的属性的更改。
### 8. 新 Manifest 文件输出到指定目录: `-o`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -o ../new_androidmanifest.xml -d 1
```
将 debuggable 改为 true 后的 Manifest 文件输出为`new_androidmanifest.xml`
### 9. Manifest 文件中的新增 MetaData 标签: `-md`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -md xposedminversion:53 -md xposedmodule:true
```
如此，文件中新增了一个Meta-data标签：
```
         <meta-data
            android:name="xposedminversion"
            android:value="53" />
         <meta-data
            android:name="xposedmodule"
            android:value="true" />
```
### 10. 删除 Manifest文件中的MetaData 标签: `-dmd`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -dmd xposedminversion -dmd xposedmodule
```
`android:name` 等于 `xposedminversion` 或 `xposedmodule` 的 meta-data 标签会被**整个删除**，输出的 manifest 里不会再出现这两个节点。

如果只是想把某个已有 meta-data 的值改掉，可以同时使用 `-dmd` 和 `-md`，先删除再新增：
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -dmd xposedmodule -md xposedmodule:99
```
这样输出中只会留下一个 `android:value="99"` 的 xposedmodule 节点。

### 11. 新增或替换 Activity 节点: `-act`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -act com.example.NewActivity
```
如果需要同时设置 `android:exported`，可以直接在名称后面附上布尔值或 `1/0`：
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -act com.example.LoginActivity:true
```
参数格式：`activity-name[:exported]`

**说明**：`-act` 目前只处理 `name` 和 `exported` 两个属性。新增时会写入这两个必要字段；若同名 Activity 已存在，则只更新当前支持的属性，不会扩展其他复杂配置。

### 12. 设置 extractNativeLibs 属性: `-e`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -e 1
```
新的 manifest 文件中，application 标签下会增加或修改`android:extractNativeLibs="true"`；传 `0` 则改为 `false`。

### 13. 修改 uses-sdk 标签下的属性: `-ua`
```
$ java -jar ../ManifestEditor.jar ../AndroidManifest.xml -ua android-targetSdkVersion:28 -ua android-minSdkVersion:21
```
manifest 中已存在 uses-sdk 标签时，会新增或修改其中对应的属性。如果原 manifest 中没有 uses-sdk 标签，该选项不会新建这个标签。

### 14. 新增或删除 provider 标签
provider 的新增和删除暂时没有对应的命令行选项，需要在代码中通过 `ModificationProperty` 调用：
```
    List<AttributeItem> attributes = new ArrayList<>();
    attributes.add(new AttributeItem("name", "com.test.new.MyProvider"));
    attributes.add(new AttributeItem("authorities", "com.test.new.provider"));
    attributes.add(new AttributeItem("exported", false));
    attributes.add(new AttributeItem("grantUriPermissions", false));

    property.addProvider(attributes, "com.test.new.action.PROVIDER");

    property.addDeleteProviderAuthorities("com.test.old.provider");
```
`addProvider` 的第二个参数是 intent-filter 中 action 的 name，传 `null` 则不生成 intent-filter。

**说明**：`exported`、`grantUriPermissions` 这类属性在平台上是用 `TypedArray.getBoolean` 读取的，如果写成字符串 `"true"`，平台会按默认值处理（等同于 false）。所以这里要把值写成 `Boolean`，visitor 才会按 `TYPE_INT_BOOLEAN` 写出。

### 15. 统一改写权限名与 provider 的 authorities
同样需要在代码中调用。`permissionMapper` 会作用于 uses-permission、permission 标签，以及组件上的 `android:permission`、`android:readPermission`、`android:writePermission`；`authorityMapper` 会作用于 provider 的 `android:authorities`（多个 authorities 用 `;` 分隔时会逐个改写）：
```
    property.setPermissionMapper((type, permission) -> permission.replace("com.old.", "com.new."));
    property.setAuthorityMapper(authorities -> authorities.replace("com.old.", "com.new."));
```
# **修改 apk 中的 Manifest 文件**
```
$ java -jar ../ManifestEditor.jar ../original.apk -o ../new_build_unsigned.apk -d 1
```
将original.apk文件里的manifest的debuggable属性改为true后，输出未签名的新apk: `new_build_unsigned.apk`。
如果需要用内置的签名文件对apk进行签名，加上`-s`即可：
```
$ java -jar ../ManifestEditor.jar ../original.apk -o ../new_build.apk -d 1 -s
```
`new_build.apk`文件目录会生成另外一个签名后的apk：`new_build_signed.apk`。

默认使用的是jarsigner命令对apk签名，假如签名失败，可自行对`new_build.apk`进行签名。
# **Android 或者 Java 代码中使用**
也可以将`ManifestEditor.jar`文件导入到Android或Java工程中使用，接入方法为：
```
    ModificationProperty property = new ModificationProperty();

    property.addManifestAttribute(new AttributeItem(NodeValue.Manifest.PACKAGE, "wind.new.pkg.name").setNamespace(null))
                .addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_CODE, 1))
                .addManifestAttribute(new AttributeItem(NodeValue.Manifest.VERSION_NAME, "1123"))
                .addUsesPermission("android.permission.READ_EXTERNAL_STORAGE")
                .addUsesPermission("android.permission.WRITE_EXTERNAL_STORAGE")
                .addMetaData(new ModificationProperty.MetaData("aa", "11"))
                .addMetaData(new ModificationProperty.MetaData("aa", "22"))
                .addApplicationAttribute(new AttributeItem(NodeValue.Application.DEBUGGABLE, true))
                .addApplicationAttribute(new AttributeItem(NodeValue.Application.NAME, "my.app.name.MyTestApplication"))
                .addApplicationAttribute(new AttributeItem("appComponentFactory", "my.app.name.MyAppComponentFactory"));

    String inputManifestFilePath = "../../AndroidManifest_old.xml";
    String outputManifestFilePath = "../../AndroidManifest.xml";

    FileProcesser.processManifestFile(inputManifestFilePath, outputManifestFilePath, property);

    String inputApkFilePath = "../../original_old.apk";
    String outputApkFilePath = "../../new_build_unsigned.apk";

    FileProcesser.processApkFile(inputApkFilePath, outputApkFilePath, property);
```
`AttributeItem` 的第二个参数如果是 `String`，写出的类型是 `TYPE_STRING`；如果是 `Boolean`，写出的类型是 `TYPE_INT_BOOLEAN`。对于 `debuggable`、`exported` 这类平台按布尔读取的属性，必须传 `Boolean`。
# **License**
Originally forked from [axml](https://github.com/Sable/axml).
```
Copyright 2020, WindySha

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this work 
except in compliance with the License. You may obtain a copy of the License in the 
LICENSE file, or at:

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the
License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
ANY KIND, either express or implied. See the License for the specific language governing
permissions and limitations under the License.
```

