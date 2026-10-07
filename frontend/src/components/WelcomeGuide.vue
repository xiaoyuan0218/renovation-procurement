<script setup>
import { closeGuide, guideVisible } from '../guide'
</script>

<template>
  <el-dialog v-model="guideVisible" width="520px" :close-on-click-modal="false"
             append-to-body @close="closeGuide">
    <template #header>
      <div class="guide-title">欢迎用采知道</div>
    </template>

    <p class="guide-lead">
      一套自己部署的装修采购账本：东西买没买齐、花了多少、还欠多少，
      都在这一个页面里。数据存在你自己的机器上，不经过任何第三方。
    </p>

    <ol class="guide-steps">
      <li>
        <b>先把房间和类别列出来</b>
        <span>「设置 / 数据 → 分组」加客厅、主卧这些房间，「分类」加灯具、五金这类归类。</span>
      </li>
      <li>
        <b>再往清单里加物料</b>
        <span>「清单」页点右下角新增，填名称、总量、单价；要买在哪个房间就先写进分配。</span>
      </li>
      <li>
        <b>买完就记一笔</b>
        <span>点物料卡片上的状态标签，填数量金额和日期。状态会自动变成「部分已买」或「已买完」。</span>
      </li>
    </ol>

    <div class="guide-tips">
      <span>顶部切换清单</span>
      <span>底部三个页：总览看数、清单记明细、矩阵按房间排布点</span>
      <span>改动都会记进「设置 / 数据 → 日志」，最近的还能一键回退</span>
    </div>

    <template #footer>
      <el-button type="primary" @click="closeGuide">开始用</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.guide-title { font-size: 17px; font-weight: 600; color: var(--ios-label); }
.guide-lead {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ios-label-2);
}
.guide-steps {
  margin: 0 0 16px;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.guide-steps li { font-size: 13px; line-height: 1.7; color: var(--ios-label); }
.guide-steps b { display: block; font-weight: 600; }
.guide-steps span { color: var(--ios-label-2); }
.guide-tips {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--ios-fill);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ios-label-2);
}
</style>
