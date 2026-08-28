package com.fabio.tiktokcaption

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Fica esperando o TikTok abrir na tela de descrição/legenda e cola automaticamente
  * o texto gerado pelo Gemini (guardado em SharedPreferences pela MainActivity).
   *
    * Heurística: em vez de procurar um id de recurso fixo do TikTok (que muda a cada
     * atualização do app e não é público), procura o primeiro campo de texto editável
      * visível na janela ativa. Funciona bem quando a tela de legenda tem um único campo
       * de texto — se o TikTok tiver mais de um campo editável nessa tela, pode ser
        * necessário ajustar essa lógica depois de ver a gravação real da tela.
         */
class CaptionAccessibilityService : AccessibilityService() {

      private val targetPackages = setOf(
                "com.zhiliaoapp.musically",   // TikTok
                "com.ss.android.ugc.trill",   // TikTok (variante regional)
                "com.instagram.android",      // Instagram
                "com.google.android.youtube"  // YouTube
            )

          override fun onAccessibilityEvent(event: AccessibilityEvent?) {
                    val packageName = event?.packageName?.toString() ?: return
                    if (packageName !in targetPackages) return

                    val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                            val pendingCaption = prefs.getString(Prefs.KEY_PENDING_CAPTION, null) ?: return

                    val root = rootInActiveWindow ?: return
                    val target = findEditableNode(root) ?: return

                    val args = Bundle()
                            args.putCharSequence(
                                          AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                          pendingCaption
                                      )
                                    val success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                                            if (success) {
                                                          prefs.edit().remove(Prefs.KEY_PENDING_CAPTION).apply()
                                                                      Toast.makeText(this, "Legenda colada! Confira e publique.", Toast.LENGTH_LONG).show()
                                            }
          }

              private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                        if (node.isEditable) return node
                        for (i in 0 until node.childCount) {
                                      val child = node.getChild(i) ?: continue
                                      val found = findEditableNode(child)
                                                  if (found != null) return found
                        }
                                return null
              }

                  override fun onInterrupt() {}
}
