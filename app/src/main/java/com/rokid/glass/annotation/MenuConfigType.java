package com.rokid.glass.annotation;

import androidx.annotation.Keep;

@Keep
public interface MenuConfigType {
    interface MenuInfoType {
        String MENU_FACE_RECOG = "menu_face_recog";   // 护学岗
        String MENU_FACE_ONLINE_RECOG = "menu_face_online_recog";
        String MENU_LPR_RECOG = "menu_lpr_recog";   // 护学岗
        String RECEIVE_MSG = "receive_msg";   // 接收消息
        String SEND_MSG = "send_msg";   // 发送消息
        String MENU_TAKE_PHOTO = "menu_take_photo";   // 拍照
        String MENU_HIDDEN_RISK = "menu_hidden_risk";   // AI识患
    }
    interface ZSMenuInfoType {
        String MENU_INSPECTION_SCHOOL = "menu_inspection_school";   // 护学岗
        String MENU_PUTUO_LARGE_FLOW = "menu_putuo_large_flow";     // 普陀山大客流
        String MENU_MIGRANT_POPULATION = "menu_migrant_population"; // 流动人口
        String MENU_CALL_THE_POLICE = "menu_call_the_police"; //接出警
        String MENU_TRAFFIC_POLICE_CHECKPOINT = "menu_traffic_police_checkpoint";   // 交警设卡稽查
        String MENU_INSPECTION_SCHOOL1 = "menu_inspection_school1";   // 护学岗
        String MENU_PUTUO_LARGE_FLOW1 = "menu_putuo_large_flow1";     // 普陀山大客流
        String MENU_MIGRANT_POPULATION1 = "menu_migrant_population1"; // 流动人口
        String MENU_TRAFFIC_POLICE_CHECKPOINT1 = "menu_traffic_police_checkpoint1";   // 交警设卡稽查
        String MENU_INSPECTION_SCHOOL2 = "menu_inspection_school2";   // 护学岗
        String MENU_PUTUO_LARGE_FLOW2 = "menu_putuo_large_flow2";     // 普陀山大客流
        String MENU_MIGRANT_POPULATION2 = "menu_migrant_population2"; // 流动人口
        String MENU_TRAFFIC_POLICE_CHECKPOINT2 = "menu_traffic_police_checkpoint2";   // 交警设卡稽查

        String MENU_FLOATING_POPULATION_ENTERPRISE = "menu_floating_population_enterprise";//入企检查

        String MENU_FLOATING_POPULATION_HOUSE = "menu_floating_population_house";//入户检查
    }
}
