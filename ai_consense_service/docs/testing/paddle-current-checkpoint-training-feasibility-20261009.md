# 当前 Paddle checkpoint 的训练与回接可行性

日期：2026-10-09，Asia/Shanghai。执行者：Codex。状态：官方公共文本、固定模型配置及本机已安装源码的研究；没有安装训练环境、下载训练权重、加载模型、训练、导出或执行新推理。本文不将源码支持或旧模型教程记为当前 checkpoint 的训练验收。先读 [现行范围](../oer-scope-and-rules.md) 与 [handoff](../HANDOFF-ocr-embedding-rerank-20261008.md)；实际错误与精度证据见 [原始 PDF 实测](./original-pdf-dual-parser-bf16-validation-20261009.md)。

## 结论与首选路线

**PP-DocLayoutV3 有同型号的官方完整训练、数据检查、评价和导出路线；PaddleOCR-VL-1.6 有可开发的训练接口及官方 HF→原始键名导出／Paddle 加载机制，但尚未查到或执行当前 revision 的官方端到端训练配方。** 所以“可以开展兼容性 pilot”成立，“改一个模型路径就已保证微调和回接成功”不成立。[S1–S8]

**最新执行顺序：本机调试、冻结完整包，再部署超算。** 用户明确说明超算平台改代码会触发大量扫描，因此默认在本机RTX A4000 16GB完成配置／参数消融、必要的tiny训练兼容pilot、导出及native回接；weights／config／processor／依赖／代码绑定身份后再上传。H80080GB平台用于推理部署兼容、资源、吞吐和模型切换验收，不作为现场训练或代码迭代场所。本文80GB训练资源算术仅保留作参考，不再构成默认工作流；任何现场失败均保留证据，修订在本机完成、重新冻结后重新部署，不现场改码。[用户最新明确指示]

| 对象／路线 | 已证实 | 当前没有证明 | 工程判断 |
| --- | --- | --- | --- |
| PP-DocLayoutV3，PaddleX／PaddleDetection full fine-tune | 官方同型号 trainer、COCO mask＋read_order 数据、训练 pdparams 链接、评价与静态导出；当前 native 使用静态推理模型 | 官方训练 pdparams 与已固定 HF 推理 revision 的权重数值同一；本项目训练后静态输出已兼容 | 标注工作量较大；训练接线难度中等，首选用于确证的区域漏检／类别／读序问题 |
| VL-1.6，Transformers full／冻结模块训练 | 当前固定 checkpoint 的 forward 支持 labels／loss；gradient checkpointing；官方模型卡支持 Transformers 推理 | 当前 checkpoint、所选 Transformers commit、训练数据与 native 回接已跑通 | 接线难度中等；完整兼容性和数据监督是主要工作，不能直接跳到大量训练 |
| VL-1.6，Transformers＋PEFT LoRA | PEFT 可显式选择线性层并合并 adapter；当前模型存在可选的语言注意力／MLP线性层；有官方原始键名逆导出机制 | 当前 VL-1.6 专用官方 LoRA recipe；LoRA梯度、merge和原生加载已验证 | 对确证的识别／表格序列错误，首选先做小范围、冻结视觉的语言 LoRA pilot，再决定是否扩展 |
| VL-1.6，ERNIEKit OCR-VL-SFT | 官方 OCR workflow 有 full／LoRA分支、freeze_vision、BF16、recompute、HF导入／导出设置 | 当前发布教程和示例仍是原版 PaddleOCR-VL-0.9B；它们没有证明 VL-1.6 revision 兼容 | 可作为 Paddle 路线备选；必须先核验 config、state keys、processor与保存产物，不能照搬旧教程成果 |
| 普通 PaddleX `doc_vlm` train | 当前官方 trainer 明确抛 UnsupportedAPIError | 通过常规 PaddleX train 直接训练 VL-1.6 | 当前不是训练入口；须走训练工具或自建 training runner，不能把推理支持当训练支持 |

首选顺序是先按错误归因选择需要调整的模块：布局未裁入原文先处理 detector／裁块；裁块完整但字符或表格序列持续错误才做 recognition VLM pilot；跨页归组、标题关联、空槽恢复、原生编辑状态按各自结构／原生链路处理。识别 VLM 没有看到的区域不能靠其微调恢复；模型输出之后被后处理删除的信息也不能仅靠训练证明已解决。当前两精度共同失败不证明训练必需，既有23页开发样本及37项关键检查不是独立训练留出或全文 gold。[S1；本项目原 PDF 报告]

## 1. 精确模型与官方版本边界

- Recognition VLM：`PaddlePaddle/PaddleOCR-VL-1.6`，revision `c5630abae1d940eafe0697512a0325494b02ab42`。固定卡声明架构兼容 VL-1.5，配置为 `paddleocr_vl`，权重存储BF16；架构兼容不是训练脚本／导出验收。固定模型卡的 Transformers 示例使用 BF16，但主要证明推理。[S1、S2]
- Layout：`PaddlePaddle/PP-DocLayoutV3`，revision `241f8bdfc77a7c7bee915a5057aaee58c235a8d3`。当前本机目录是 `inference.json`＋`inference.pdiparams`＋`inference.yml` 的静态推理模型；模型卡也区分 Paddle 权重与另一个 safetensors repo。当前25类label顺序保存在研究证据 `installed-layout-inference.yml`。不能将它冒称训练 `.pdparams`。[S3、L1]
- 本机验收配方仍是 PaddleX 3.6.0、Paddle 3.3.1、native VLM 官方 mixed BF16。研究用最新公开 PaddleX develop 固定到 `c852371c701d83cd21955b2963834825920ffebb`，与本机版本分别记录；最新训练／转换功能不自动证明本机 wheel 具备相同功能。[S4–S7、L2–L4]
- 官方 ERNIE 默认分支是 `release/v1.5`，本研究固定 commit `790a50b045d1aca2753d5395d8bec0806b2e6925`。`docs/paddleocr_vl_sft.md` 标题、下载地址、model_name及推理命令均指原版 `PaddlePaddle/PaddleOCR-VL`／0.9B，没有 VL-1.6 专用 recipe。本研究也保留 release/v1.4 文本；它不能充当本版本通过证据。[S8–S11]
- Transformers 研究固定 commit `9c59c0c6733990bbf8b1be7cb93b97e57dc73c39`；PEFT 源码使用固定 tag `v0.18.0`，这里只核查接口，不指定已安装组合、不保证该PEFT版本与该Transformers commit运行兼容。[S12–S15]

## 2. PP-DocLayoutV3：训练路径与标注要求

官方 `layout_analysis` trainer继承 object detection trainer，并使用 `COCOInstSegDataset`。所需数据不是只有矩形bbox：每页有区域类别、bbox、实例polygon／mask、iscrowd和阅读顺序；每个区域必须有 `read_order`，同页应从0起形成连续序列。官方配置显式使用 `gt_poly`、`gt_read_order`，有class、bbox、GIoU、mask、dice、order损失，其中order系数50。类别、轮廓和读序应由原图标注，不用OCR当前错误输出充当真值。[S4–S6]

示例目录为 `images/`、`images_mask/`、`annotations/instance_train.json` 和 `instance_val.json`。单annotation示例是 `[x,y,width,height]` bbox、polygon segmentation及 `read_order`。完整页中应保留页眉／页脚／合同标识、条款号、脚注、表格和标题等当前25类语义。对于扫描SL、2b附录等问题，不能仅把目标文字框出来而让旁边正文／表头／脚注无标签；裁块扩大后的原图位置、读序和表组边界还需完整链路验证。[S4；本项目范围]

**注意类别身份陷阱：** 官方模块demo配置 `Train.num_classes=11`，repo底层配置及已部署模型为25类。若目的是保持当前parser语义，不应直接用11类demo替换25类模型；必须明确类别ID→名称→模型head的映射，固定原25类顺序并核验输出。改类别数会改变head，不能称完全继承当前checkpoint。官方专用training `.pdparams` 链接提供训练起点，但本轮未下载，其字节身份和与当前固定推理模型的权重等价未证明。[S4–S6、L1]

路线为独立环境安装PaddleX Detection训练能力，检查本地COCO数据，显式单卡 `Global.device=gpu:0`，用训练 `.pdparams` fine-tune，评价，导出新的静态推理目录，再将 layout model dir接入独立parser测试。官方文档说明产生dynamic／static权重，静态作为默认推理，并支持V3 `.pdparams`→safetensors转换用于dynamic／Transformers引擎；**当前 native 静态配方首选静态导出，不为训练顺便切后端。** 本机PaddleX3.6.0能否执行最新转换命令另验，不能把develop文档直接当现有wheel能力。[S4–S7]

这是一条可证的同型号常规fine-tune路线。本研究没有发现当前V3专用LoRA recipe，不建议先改造检测器LoRA。800×800输入、mask分支、增强、batch和optimizer决定实际训练峰值；不能拿130MB静态权重大小承诺训练峰值或时长。27vCPU不应照抄底层 `worker_num=24`、高prefetch的默认配方，先控制workers、CPU／RAM／磁盘压力再调。[S6；资源推论]

## 3. VL-1.6：真实训练入口与数据

固定HF revision随附 `modeling_paddleocr_vl.py`：顶层forward接收 `labels`、pixel_values、image_grid_thw等，并用shifted token交叉熵计算loss；文本／视觉基类声明gradient checkpointing。固定Transformers实现也有labels／loss、GradientCheckpointingLayer及支持标志。这是训练可开发的直接源码证据；没有证明我们的collator、batch、视觉梯度或PEFT wrapper已经运行成功。[S2、S12]

首选训练数据是**原图区域＋原推理任务prompt＋经过原件核对的准确输出**，保留源文件hash、页、bbox、完整表组及裁图关系。普通文字用文字识别任务；表格不能用普通Markdown随意替代既定输出语法，必须核验当前parser采用的OTSL／表格任务token，rowspan／colspan、空槽、表头及末行都需要完整监督。原版ERNIE教程明确文字 `OCR:`、表格OTSL、公式LaTeX、图表Markdown；这些能说明既有任务格式，当前1.6同任务、processor及token一致性仍须核验，不将教程里示例答案混入对应独立评价。[S8、S2；项目规则]

ERNIE旧版数据是每行一个JSON：`image_info[{image_url,matched_text_index}]`，`text_info[{text,tag}]`；输入prompt为`mask`、答案为`no_mask`并交替。它不是HF Trainer的现成collator。HF路径需经固定processor／chat template建立图像和token输入，对prompt、图像占位、padding等设`labels=-100`，只监督正确答案，保存token与图像覆盖；严禁为了过检静默裁掉表格末行、否定词、条件或空槽。训练时目标token与图像patch预算需另计，不能直接把推理8192输出预算当训练长度。[S8、S12；项目规则]

LoRA建议先冻结visual，显式限定**语言层**注意力／MLP线性模块并记录实际匹配名称、shape、trainable count。只传`q_proj/v_proj`后缀可能也匹配视觉层；必须按当前实际模型命名限定前缀，不将`all-linear`当默认安全选项。开始可用低rank作兼容pilot，rank及是否训练projector由错误／梯度检查决定，不据本研究认定最优。冻结视觉有助于控制更改范围，但不能宣称它会修复视觉辨认能力不足。[S12、S14、S15；工程建议]

ERNIE OCR workflow本身有LoRA分支，配置 `fine_tuning: Full`，`compute_type: bf16`、`recompute: True`、`convert_from_hf: True`、`save_to_hf: True`，支持freeze_vision。LoRA helper依据fused或HF层选择qkv／qkv分离、o、MLP等，并将仅LoRA标为可训练。它没有证明当前1.6的所有模块命名和tensor shapes覆盖正确；示例的packing_size=8、max_seq_len=16384、batch1不是当前合同数据和H800的验收配方。旧教程的A800示例耗时不用于本项目H800训练时长。[S9–S11]

## 4. HF／Paddle回接已有机制与剩余门槛

回接有第一方源码路线，不必直接认定要从零写权重转换器：

1. 当前Transformers内部把旧 `mlp_AR` 改成 `model.projector`，`visual`改成`model.visual`，旧文字`model.*`改成`model.language_model.*`。官方 `conversion_mapping.py` 明确列出 `paddleocr_vl` 的这些映射。[S13]
2. 同commit的 `save_pretrained(..., save_original_format=True)` 默认启用原始格式保存，调用 `revert_weight_conversion`。这提供官方逆映射出口。必须在pilot检查实际导出的键名；不能只看模型文件存在或默认参数名称。[S13]
3. 当前本机PaddleX predictor用 `from_pretrained(..., convert_from_hf=True)`；native模型有HF state映射、线性weight转置键名单，并处理qkv和gate/up融合。训练产物若保持原始HF键、结构和配置，存在回到native的可证加载机制。[L2–L4、S7]
4. PEFT adapter-only不是完整native checkpoint。需先核验adapter合并／卸载所得完整模型，再按原始键名保存。不要给native直接指向`adapter_model.safetensors`，也不要仅把文件后缀改成`.pdparams`。ERNIE export有LoRA merge分支，但其针对当前1.6的产物仍需pilot。[S11、S14、S15]

**仍未验证的内容：** HF新config可能从平坦字段变为text_config／vision_config结构；即使权重逆映射成功，也需核查Paddle当前config读取的hidden_size、head_dim、mrope、token IDs、bias、projector／vision配置。不能只修正键名后让未知字段落到默认值。原固定tokenizer、processor、chat template、inference.yml（model_name为`PaddleOCR-VL-1.6-0.9B`）及新weights manifest必须完整保留；只有结构不变时才能明确选择恢复原config，不能用旧config掩盖真实架构变化。[S2、S13、L2–L4]

Paddle native推理的 `_keep_in_fp32_modules=["visual","mlp_AR"]` 仍遵循官方策略；训练时BF16／FP32模块政策与推理时实际cast分别核验。回接不能擅自把visual全转BF16，训练产物不追认新的精度配方。weights loading的missing／unexpected／mismatched keys不能当普通warning忽略，必须逐项解释并阻止未知重新初始化。当前原模型本机完整推理已跑通，只证明原始产物加载，不证明新训练／merge产物等价。[L2–L4；原PDF精度报告]

## 5. 本机16GB调试、冻结包与超算推理验收

默认执行机为本机RTX A4000 16GB。先完成不更新权重的配置／参数消融；存在明确训练目标时，再做本机tiny训练兼容pilot及导出native回接。**本机16GB训练峰值尚未验证，原模型16GB推理成功不能证明训练可容纳。** 先用batch1、冻结视觉、限定语言LoRA目标、use_cache=False、gradient checkpointing／recompute和受控packing／workers核查真实峰值；BF16仍保留敏感计算FP32。不能为装入显存而截断关键完整表格目标、末行、空槽、表头、否定或条件。完整目标仍无法容纳时，保留该训练分支未通过的状态，另行验证CPU optimizer offload／更受控的模块方案，而非默认移到超算现场训练或把截断样本记为完整结构通过。[S2、S9、S12；用户最新指示]

超算资源仍按用户确认的H80080GB、250GB RAM、650GB高速盘＋1250GB普通盘和27vCPU规划；模型卸载／加载可控，可保留LLM RAM副本。但该能力用于部署后分阶段推理／切换，并不将平台变成默认训练场所。LLM身份／量化／warm reload耗时和host峰值仍独立验证；约60GB权重RAM副本不证明所有缓存、临时副本和其他进程可同时容纳。[用户最新资源与流程确认；handoff]

作为资源参考，VL共958,588,736参数。常见Adam混合精度算式为BF16weights2B＋BF16grads2B＋FP32master4B＋两份FP32moments8B＝16B／参数，约15.34GB（14.28GiB）；若grad也是FP32则18B／参数，约17.25GB（16.07GiB）。这已经接近／超过本机16GB总额，且**只是假设的persistent state算术，不含activation、长序列logits、attention workspace、视觉FP32策略及保存峰值**，不能默认在A4000做full fine-tune。LoRA缩小可训练状态，但仍需基模型前向与部分反向激活；80GB独占的full训练空间算术保留为参考，也不保证任意整页／16K训练。当前默认并非到平台训练。[参数数来自原PDF报告；计算推论；用户最新指示]

最小pilot及所有兼容性代码先在本机独立环境和新输出目录完成，按以下顺序验收；本文均未执行：

1. **零步roundtrip先行。** 固定VL1.6原revision、所选HF实现和processor；加载→不训练→原始键名保存→Paddle native加载。比较完整key/shape/dtype、tensor值、config／tokenizer／processor身份，以及同输入关键输出／结构。针对LoRA额外验证零初始化adapter→merge仍保留基模型。完成这个门槛前不做大批训练。
2. **本机单样本forward／backward。** 从已核对原图制作1条文字、1条完整表格样本；检查图像token、答案mask、有限loss、真实非零目标梯度、冻结模块无梯度。batch1、冻结visual、限定语言LoRA、BF16（敏感计算保留FP32）、use_cache=False、gradient checkpointing／recompute；减少packing和workers，实际测A4000训练峰值。受控图像／答案预算不得删掉该表完整关键目标，不同时改attention后端、量化、输出语法。
3. **单步→保存→merge→native reload。** 一个optimizer step后确认只有许可参数变化，完整导出，再用native配方加载；确认未知missing／mismatched为零。未合并adapter与合并checkpoint分别保存，父revision及训练源码／数据hash写入manifest。
4. **布局pilot独立。** 先用同型号training pdparams核验权重来源与类别身份，少量完整COCO mask＋read_order样本检查loss／训练／静态export，再接入现有25类pipeline。若训练起点与固定HF推理权重不能证明等价，单独建立预训练起点基线，不能把基线变化与fine-tune收益混算。
5. **资源记录后再放大。** 分别测load、forward、backward、optimizer step、export的allocated／reserved、整卡采样、RSS及磁盘写入峰值；保存step时间但不提前承诺训练总时长。先降低batch／packing／prefetch，再决定是否需要offload；一开始不引入QLoRA或改量化以增加兼容变量。
6. **质量验收。** 先检查少量学习信号，再用文件级、近重复／派生同组的真实留出检验文字、关键数字／否定／条款号、完整表格拓扑／空槽、漏块、读序与资产映射；报告改善、退化和unknown。过拟合pilot能证明训练接线，不能证明泛化。
7. **本机冻结完整部署包。** 明确新weights／adapter合并产物及原revision、config、tokenizer、processor／chat template、layout类别与导出文件、推理配方、代码commit、依赖lock／目标Linux wheel或容器digest、离线启动脚本、验证样本与manifest/hash；先完成HF原始格式／native回接及所需兼容代码。不能把Windows依赖原样视为Linux/H800运行环境，目标环境构建、入口和离线资源清单在本机先准备及核查。打包产物和未通过项分别记录，未执行不冒称完成。
8. **超算仅验收冻结包推理。** 按已固定入口测试部署兼容、实际device字节、完整页／表组推理峰值、吞吐、LLM卸载／暖加载与阶段切换；H800性能须现场实测，但不现场训练／调代码。若驱动、依赖、架构或加载失败，保存错误与包身份，回到本机修订、复验并重新冻结；平台扫描成本是该执行顺序的实际约束。

实现难度：DocLayout同型号训练入口较现成，主要成本在完整mask／读序标注、25类身份及静态导出回归；VL LoRA训练本体不需要重写整个模型，难度集中在当前checkpoint的数据处理、梯度与adapter模块覆盖、HF原始格式／config回接验证。官方逆映射机制使回接比从零开发转换器容易，但最后一段当前版本的真实兼容性仍须pilot。完整视觉full fine-tune、修改架构或追加编辑状态输出词汇会明显增加训练、格式和后处理成本，应在明确错误与独立标签支持后另开试验。[上述来源与工程评估]

## 6. 来源与本机证据

公共文本和本机已安装文本副本均保存在忽略目录 `D:\R&D\ConSense\.local\state\oer-training-research-20261009\paddle\`。`sources-manifest.json`绑定URL或本机源路径、抓取UTC日期、bytes及SHA256；只保存小文本，未下载模型／数据集。首次猜测URL的404及GitHubAPI403保留，不以失败页面证明“不支持”；实际结论用以下成功来源。分支文档另固定commit；HF fixed revision是研究对象，不能自动固定训练repo依赖。

| 来源 | 第一方链接与核查内容 | 本机证据文件 |
| --- | --- | --- |
| S1 | [VL1.6固定卡](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/blob/c5630abae1d940eafe0697512a0325494b02ab42/README.md)，53／81行架构兼容，154–242行HF推理 | hf-vl16-readme.md |
| S2 | [固定config](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/blob/c5630abae1d940eafe0697512a0325494b02ab42/config.json)、[固定model源码](https://huggingface.co/PaddlePaddle/PaddleOCR-VL-1.6/blob/c5630abae1d940eafe0697512a0325494b02ab42/modeling_paddleocr_vl.py)，527／1258行checkpointing，2125–2294行labels／loss | hf-vl16-config.json、hf-vl16-modeling.py、hf-vl16-processor.json、hf-vl16-preprocessor.json、hf-vl16-inference.yml |
| S3 | [LayoutV3固定卡](https://huggingface.co/PaddlePaddle/PP-DocLayoutV3/blob/241f8bdfc77a7c7bee915a5057aaee58c235a8d3/README.md)，Paddle／safetensors与mask／order模型身份 | hf-layoutv3-readme.md |
| S4 | [PaddleX layout_analysis官方文档](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/docs/module_usage/tutorials/ocr_modules/layout_analysis.en.md)，344–643行训练／数据／导出 | paddlex-layoutanalysis.en.md |
| S5 | [Layout trainer](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/modules/layout_analysis/trainer.py)、[exportor](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/modules/layout_analysis/exportor.py) | paddlex-layout-trainer.py、paddlex-layout-exportor.py |
| S6 | [模块配置](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/configs/modules/layout_analysis/PP-DocLayoutV3.yaml)、[检测repo配置](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/repo_apis/PaddleDetection_api/configs/PP-DocLayoutV3.yaml)，11／25类、mask／order loss、workers | paddlex-layoutv3-module.yaml、paddlex-layoutv3-repo.yaml |
| S7 | [PaddleX doc_vlm trainer](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/modules/doc_vlm/trainer.py)不支持普通train；[native模型](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/inference/models/doc_vlm/modeling/paddleocr_vl/_paddleocr_vl.py)与[loader](https://github.com/PaddlePaddle/PaddleX/blob/c852371c701d83cd21955b2963834825920ffebb/paddlex/inference/models/common/transformers/transformers/model_utils.py)提供HF转换 | paddlex-doc-vlm-trainer.py、paddlex-native-vlm-modeling.py、paddlex-native-model-utils.py |
| S8 | [ERNIE固定OCR-SFT教程](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/docs/paddleocr_vl_sft.md)，3／57／130／214行明确原版0.9B，65–103及226–280行任务格式 | ernie-v15-ocr-sft-pinned.md |
| S9 | [OCR-SFT配置](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/examples/configs/PaddleOCR-VL/sft/run_ocr_vl_sft_16k.yaml)，full／BF16／recompute／save_to_hf | ernie-v15-ocr-sft-config.yaml |
| S10 | [OCR workflow](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/erniekit/train/ocr_vl_sft/workflow.py)，358–444行config与加载，464–473冻结，658–667 LoRA；[LoRA helper](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/ernie/utils/peft_utils.py) | ernie-v15-ocr-workflow.py、ernie-v15-peft-utils.py、ernie-v15-ocr-modeling.py |
| S11 | [ERNIE模型参数](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/erniekit/hparams/model_args.py)、[export](https://github.com/PaddlePaddle/ERNIE/blob/790a50b045d1aca2753d5395d8bec0806b2e6925/erniekit/export/export.py)，LoRA配置／merge而非1.6验证 | ernie-v15-model-args.py、ernie-v15-export.py、ernie-v15-config.py |
| S12 | [Transformers模型](https://github.com/huggingface/transformers/blob/9c59c0c6733990bbf8b1be7cb93b97e57dc73c39/src/transformers/models/paddleocr_vl/modeling_paddleocr_vl.py)，415／462／759行checkpointing，1396–1400行loss | transformers-pinned-vl-modeling.py |
| S13 | [官方键映射](https://github.com/huggingface/transformers/blob/9c59c0c6733990bbf8b1be7cb93b97e57dc73c39/src/transformers/conversion_mapping.py)，880–887行；[save_pretrained](https://github.com/huggingface/transformers/blob/9c59c0c6733990bbf8b1be7cb93b97e57dc73c39/src/transformers/modeling_utils.py)，3253／3296–3299／3462–3463行save_original_format与revert | transformers-pinned-conversion-mapping.py、transformers-pinned-model-utils.py |
| S14 | [PEFT v0.18.0 LoRA配置](https://github.com/huggingface/peft/blob/v0.18.0/src/peft/tuners/lora/config.py)，显式target_modules支持；通用能力不证明1.6端到端支持 | peft-v18-lora-config.py |
| S15 | [PEFT v0.18.0 LoRA模型](https://github.com/huggingface/peft/blob/v0.18.0/src/peft/tuners/lora/model.py)，adapter处理／merge能力；当前实例仍待验 | peft-v18-lora-model.py |
| L1 | 本机已固定PP-DocLayoutV3静态目录，inference.yml为25类；原件未改 | installed-layout-inference.yml；源绝对路径见manifest |
| L2 | 本机PaddleX3.6.0 predictor，162–165行convert_from_hf=True | installed-vlm-predictor.py |
| L3 | 本机native模型，68–70行FP32保留模块，404–426转置，428起融合HF state | installed-native-vlm.py |
| L4 | 本机native loader，记录missing／unexpected／mismatched及HF转换流程 | installed-native-model-utils.py |

截至研究完成，source manifest的51份成功文本副本字节／SHA复验通过，另保留7次抓取错误。这个结果只证明研究证据完整；训练、HF→native roundtrip、改进质量、H800训练峰值和线上接入均未执行。
