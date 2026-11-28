-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.InstanceAlreadyExistsException
-dontwarn javax.management.InstanceNotFoundException
-dontwarn javax.management.JMException
-dontwarn javax.management.MBeanRegistrationException
-dontwarn javax.management.MBeanServer
-dontwarn javax.management.MalformedObjectNameException
-dontwarn javax.management.NotCompliantMBeanException
-dontwarn javax.management.ObjectInstance
-dontwarn javax.management.ObjectName
-dontwarn javax.script.ScriptEngineFactory
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

-dontwarn java.awt.BorderLayout
-dontwarn java.awt.Color
-dontwarn java.awt.Component
-dontwarn java.awt.Container
-dontwarn java.awt.Cursor
-dontwarn java.awt.Dimension
-dontwarn java.awt.Font
-dontwarn java.awt.Frame
-dontwarn java.awt.Graphics
-dontwarn java.awt.IllegalComponentStateException
-dontwarn java.awt.Image
-dontwarn java.awt.Insets
-dontwarn java.awt.Label
-dontwarn java.awt.LayoutManager
-dontwarn java.awt.MediaTracker
-dontwarn java.awt.TextArea
-dontwarn java.awt.Toolkit
-dontwarn java.awt.Window
-dontwarn java.awt.event.ActionEvent
-dontwarn java.awt.event.ActionListener
-dontwarn java.awt.event.KeyEvent
-dontwarn java.awt.event.KeyListener
-dontwarn java.awt.event.MouseEvent
-dontwarn java.awt.event.MouseListener
-dontwarn java.awt.event.WindowAdapter
-dontwarn java.awt.event.WindowEvent
-dontwarn java.awt.event.WindowListener
-dontwarn java.awt.image.ImageObserver
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.script.AbstractScriptEngine
-dontwarn javax.script.Bindings
-dontwarn javax.script.Compilable
-dontwarn javax.script.CompiledScript
-dontwarn javax.script.Invocable
-dontwarn javax.script.ScriptContext
-dontwarn javax.script.ScriptEngine
-dontwarn javax.script.ScriptException
-dontwarn javax.script.SimpleBindings
-dontwarn javax.security.auth.login.LoginContext
-dontwarn javax.servlet.ServletException
-dontwarn javax.servlet.http.HttpServlet
-dontwarn javax.servlet.http.HttpServletRequest
-dontwarn javax.servlet.http.HttpServletResponse
-dontwarn javax.swing.BorderFactory
-dontwarn javax.swing.Icon
-dontwarn javax.swing.JComponent
-dontwarn javax.swing.JFrame
-dontwarn javax.swing.JInternalFrame
-dontwarn javax.swing.JLabel
-dontwarn javax.swing.JList
-dontwarn javax.swing.JMenuItem
-dontwarn javax.swing.JPanel
-dontwarn javax.swing.JPopupMenu
-dontwarn javax.swing.JScrollPane
-dontwarn javax.swing.JSplitPane
-dontwarn javax.swing.JTextArea
-dontwarn javax.swing.JTextPane
-dontwarn javax.swing.JTree
-dontwarn javax.swing.JWindow
-dontwarn javax.swing.SwingUtilities
-dontwarn javax.swing.UIManager
-dontwarn javax.swing.border.Border
-dontwarn javax.swing.border.MatteBorder
-dontwarn javax.swing.event.ListSelectionEvent
-dontwarn javax.swing.event.ListSelectionListener
-dontwarn javax.swing.event.TreeSelectionEvent
-dontwarn javax.swing.event.TreeSelectionListener
-dontwarn javax.swing.plaf.SplitPaneUI
-dontwarn javax.swing.plaf.basic.BasicSplitPaneDivider
-dontwarn javax.swing.plaf.basic.BasicSplitPaneUI
-dontwarn javax.swing.text.AttributeSet
-dontwarn javax.swing.text.BadLocationException
-dontwarn javax.swing.text.DefaultStyledDocument
-dontwarn javax.swing.text.Document
-dontwarn javax.swing.text.MutableAttributeSet
-dontwarn javax.swing.text.SimpleAttributeSet
-dontwarn javax.swing.text.StyleConstants
-dontwarn javax.swing.text.StyledDocument
-dontwarn javax.swing.tree.DefaultMutableTreeNode
-dontwarn javax.swing.tree.DefaultTreeModel
-dontwarn javax.swing.tree.MutableTreeNode
-dontwarn javax.swing.tree.TreeModel
-dontwarn javax.swing.tree.TreeNode
-dontwarn javax.swing.tree.TreePath
# Gson relies on reflection: keep its adapters, TypeTokens and annotated fields.
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepattributes *Annotation*, Signature
-dontwarn com.google.gson.**
-dontwarn sun.misc.**
-keep class com.xyzterm.App { *; }
# Preserve classes reached reflectively from keybindings/snippets/settings JSON.
-keep class com.xyzterm.commands.** { *; }
-keep class com.xyzterm.file.** { *; }
-keep class com.xyzterm.utils.** { *; }
-keep class com.xyzterm.components.** { *; }
-keep class com.xyzterm.extension.** { *; }
-keep class com.xyzterm.App { *; }

# ANRWatchdog
-keep class com.github.anrwatchdog.** { *; }
-dontwarn com.github.anrwatchdog.**

# AndroidSVG
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**

-dontwarn sun.security.x509.X509Key
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Metadata

# slf4j missing static binders
-dontwarn org.slf4j.impl.**

-dontobfuscate

# Fix for AbstractMethodError: abstract method "double androidx.compose.ui.graphics.colorspace.DoubleFunction.invoke(double)"
-keep,allowoptimization interface androidx.compose.ui.graphics.colorspace.DoubleFunction { *; }
-keep,allowoptimization class androidx.compose.ui.graphics.colorspace.TransferParameters { *; }
-keep,allowoptimization class androidx.compose.ui.graphics.colorspace.Rgb { *; }
-keep class coil.util.** { *; }

# rust-anything JNI bridge: native method names and generated wrappers are reached by name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class rustanything.** { *; }
-keep class com.xyzterm.logic.SessionNamesKt { *; }
-keep class com.xyzterm.logic.DeepLinkCommandKt { *; }
