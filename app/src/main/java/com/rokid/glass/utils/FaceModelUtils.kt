package com.rokid.glass.utils

import com.rokid.security.glass3.sdk.base.data.recog.offline.bean.FaceModel


/**
 * Created by wjm on 2025/7/29
 */
object FaceModelUtils {

    fun findBestFaceRect(
        newFaces: List<FaceModel>,
        frameWidth: Int,
        frameHeight: Int
    ): FaceModel? {
        if (newFaces.isEmpty()) return null

        // 1. 计算中心点
//        val centerX = frameWidth / 2
//        val centerY = frameHeight / 2
//
//        // 2. 计算 480x480 的中心区域（保护边界）
//        val centerArea = Rect(
//            max(0, centerX - 240),
//            max(0, centerY - 240),
//            min(frameWidth, centerX + 240),
//            min(frameHeight, centerY + 240)
//        )
//
//        // 3. 筛选与中心区域有交集的人脸（部分包含即可）
//        val facesInCenter = newFaces.filter { face ->
//            face.rect.run {
//                // 判断两个矩形是否有交集
//                !(right < centerArea.left ||
//                        left > centerArea.right ||
//                        bottom < centerArea.top ||
//                        top > centerArea.bottom)
//            }
//        }

        // 4. 返回面积最大的人脸
//        return facesInCenter.maxByOrNull { it.rect.width() * it.rect.height() }
        return newFaces.maxByOrNull { it.rect.width() * it.rect.height() }
    }
}