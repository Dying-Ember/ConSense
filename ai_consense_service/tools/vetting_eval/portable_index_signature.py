"""Explicit source adapter for the audited v3/v4 predecessor and portable v5/v6.

Every changed production module must match the exact reviewed AST pair below.
A new statement, encoder replacement or omitted model guard requires a new
reviewed adapter. External receipt SHA, model bytes and vector seals still bind.
"""
import argparse
import ast
import copy
import hashlib
import json
import os
from pathlib import Path

from token_windows import fail
from embedding_compatibility import unique_object

PROTOCOL = 'portable-index-signature-source-adapter-v1'
ENV_PATH = 'CONSENSE_INDEX_SIGNATURE_ADAPTER'
ENV_SHA = ENV_PATH + '_SHA256'
MAX_BYTES = 4 * 1024 * 1024
# The predecessor is 0b475c3; formatting is immaterial, executable AST is exact.
APPROVED_SOURCE_ADAPTERS = {'index_integrity.py': ['101139f7424ad9c7ca267d51e7031e72efefe0f1805948820c6f368a4ceaf50c', '6b06f70323928d1b3b5eb79476997b47ac826da7813a5a3874000d8065f740ac'], 'oer_request_evidence.py': [['fd4c91f34aabbf214916bc1d9f5d927dc83fd32ac0e604b0141f88ac808f42af', 'aef3b08bea19262829e10273870365d8808e06aba9b61631c9ca967961224246'], ['aef3b08bea19262829e10273870365d8808e06aba9b61631c9ca967961224246', '054d6f3fd03c2ee61553a919a44efa04b40db0787ed290b8ac44215526f5983a']], 'oer_native_collection_server.py': ['404f96c4782274070c3764e97f3bec039710a972726cd50563345793d7996e08', '8a41a7c7dbe640c4d9ba7b15a2fa5a35572731a321bc75aee6020afaaafbc8a8'], 'unified_source_registry.py': [['58b750efde30159a963f29b9407507a1377444105d5d20dd79aaa7506196ab58', 'cbe65bb7d4666b6f7f5819f9d9a591ddbfeed8d5f6c84762dd8c94557179504e'], ['cbe65bb7d4666b6f7f5819f9d9a591ddbfeed8d5f6c84762dd8c94557179504e', '84837bfb19a1fc77039d04f4344e1e527023cf138850f00b6e9c374ea0c153e8'], ['cbe65bb7d4666b6f7f5819f9d9a591ddbfeed8d5f6c84762dd8c94557179504e', 'f06cb0ae0e20575dc1284b5f380001270d2ee23f4996e9ee899bfce41cadb984'], ['84837bfb19a1fc77039d04f4344e1e527023cf138850f00b6e9c374ea0c153e8', 'f06cb0ae0e20575dc1284b5f380001270d2ee23f4996e9ee899bfce41cadb984']], 'portable_index_signature.py': ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', 'a8fe4bbd3c1ede7ca77168be9b771bd5e37d766664e20ca8a1e1e01a2fcdb8a1'], 'wire_fixture_server.py': ['51d960a1e0d2cb84c20bb3d2001bbced7ecf3932435e95498531dd0a9393c975', '25b4fde9ccede490358c9c6b788b9cf8fcff29c1a58f2042e006bd5c7421b226'], 'build_fixed_development_baseline.py': ['831639922ef6403df357c16fe66ba3b163c96da018ab44871111448d5f0f07aa', '7e8aa76fa02b59828161adb37074be2cf3d351741ee198c1b988bf50138b69dc'], 'competition_scope.py': ['4a1c24b1385ac96027202071f17b3c9ab75125ba15fe5f83380aefbf3b3c9482', '61a0be87995c4a6994519e4b222c7b0efac5d95b08ac7339dca8115fb4585927'], 'complete_ocr.py': ['14b967d63fd1e948816ba1198cfa2abb3e19970b37b9218f1fb9c87716e6ff87', 'f44553137d7d89df9bb7581c4c3b0b785a63b9c72668951698ffd6a9583b43c2'], 'migrate_qdrant_server.py': ['da54cc0299c746b4167ff0103015b3399afa164e4e7802c64ec9866fe39ec3ea', '6d467525d9c7612b0e20bc886c8f801ea0a07ce77f33ed40d7151d200fbc31de'], 'migrated_qdrant_index.py': ['a66b75cd0db90495bf8334cd5b3d5025a140a2a6ab97f5b0288f9abfb77d63ba', 'd42192b47353a5f23a1c4b942d2a665f12a8885cf1426aad93f4d078fafd9e74'], 'native_pdf_canonical_projection.py': ['2da9654a361de3eb86347596847ac2ee2ad052eca473bc88439ed541bd6d39f6', '7d361e72198281f70173191aaa381ecf0214bccf880677e192047e5e5f9a594e'], 'native_provider_lifecycle.py': ['9c4e19dbc27abec0f9e7103915dff611f336929c2235875a2fb5c245130adaae', '94b2e916ff0c2916bcb6c17166d18c5b7d5248a4f623a6b76079c9d5a1482dbc'], 'oer_native_pdf_server.py': [['edf89ea9095b8ae0a1cfe7631a34e07d0423dcd4ef74b6bf0748e4105883d09d', '42b8f1c8d1886c7fabfccd1401d03b7bb747a341305db0084721a5116b16a677'], ['42b8f1c8d1886c7fabfccd1401d03b7bb747a341305db0084721a5116b16a677', 'c6df134b8fbc8f2bdeda9e78b78431b774c71038c960fdd6d41570c388636db3']], 'oer_server.py': [['eab515fe3216ba465d98b539572d6bb1c67633afaa899feba93ee1116d63830a', '44f5c1f6b897f87960375c4355fcb0b1b7f7370cbc3f38615fa91a654fc88ca9'], ['44f5c1f6b897f87960375c4355fcb0b1b7f7370cbc3f38615fa91a654fc88ca9', '00b2ade81fb60d8e17d269d18c0360fc2fc094d74d6af356f220d58b952089a9'], ['1fe19f582d10a3f0906ae83707a7fb97054045794fd0a131d82809150fce8688', '3ac5f3fa8338a6bf9ca8caea8a7cd33fd81ced5465cf6f23c3be187da18bcf54'], ['1fe19f582d10a3f0906ae83707a7fb97054045794fd0a131d82809150fce8688', 'fd40d6c5f3bee24bf56f86933a805a68e5b7251497cc5850c01b7ab8e19aa71b'], ['3ac5f3fa8338a6bf9ca8caea8a7cd33fd81ced5465cf6f23c3be187da18bcf54', 'fd40d6c5f3bee24bf56f86933a805a68e5b7251497cc5850c01b7ab8e19aa71b'], ['44f5c1f6b897f87960375c4355fcb0b1b7f7370cbc3f38615fa91a654fc88ca9', 'fd40d6c5f3bee24bf56f86933a805a68e5b7251497cc5850c01b7ab8e19aa71b'], ['00b2ade81fb60d8e17d269d18c0360fc2fc094d74d6af356f220d58b952089a9', 'fd40d6c5f3bee24bf56f86933a805a68e5b7251497cc5850c01b7ab8e19aa71b']], 'paddle_native_server.py': ['4b02e40459f10cc7f6c2d2f615ba7864ddd7fae59a95568b2089b9492fe1081d', 'a6d7b24fee6557947c48e2bbca33ef7ec4986e466a9cc415aff7f3b19fb19704'], 'retrieval_source_units.py': [['200566fa827b229eeadb1830f9709e81dc47f5bfebfbbd122685cfe9449d69b9', '9e3ed0b78889d484036af72ccb31278fdb85ebb01a24c83d9ac851f941729574'], ['9e3ed0b78889d484036af72ccb31278fdb85ebb01a24c83d9ac851f941729574', '4e7894e60bfc9117d2296c2fd609f47cf2b8fe91569bbfac925fd5bf13da0f5b'], ['9e3ed0b78889d484036af72ccb31278fdb85ebb01a24c83d9ac851f941729574', '60e661ff4fe55519b9548c4dfd73c7e955ebc1feaed56f1ff244e5dbdab73a1a'], ['4e7894e60bfc9117d2296c2fd609f47cf2b8fe91569bbfac925fd5bf13da0f5b', '60e661ff4fe55519b9548c4dfd73c7e955ebc1feaed56f1ff244e5dbdab73a1a']], 'server.py': [['4959f58c97c1f0e2a045536b7635a260b850d1c26f631aded8aba467246fefb2', '0d08bd9cb1f34a1926aee1021cbcc47c75efec8a923bb6e26ff09e52da948f21'], ['0d08bd9cb1f34a1926aee1021cbcc47c75efec8a923bb6e26ff09e52da948f21', 'ec622f0c717eac8fc54d7c304e0690164a783daf968e436f760730886017d875']], 'source_read_server.py': [['df18ca19742a3bdd691c86162e96e84316c643ee3d61811c06f6429158c15629', '71d426bfa9c14bcdaefcae79c5b469ba8c73da0ba0365dc927a49f7fed7f2539'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', 'b0b0a59bfc1fb03be30614c74d5c0d1a148cb7e20db1cc035f521e39a6c0eaed'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', '3ec37b40597936cec74c92192f3a28ae08736fb9d0c8f9e1c9958702a28d59b4'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', 'c43dd619f429ed5059ca9d085f67b53dc32e536dcf98090a7ec0690aa364e7bd'], ['3ec37b40597936cec74c92192f3a28ae08736fb9d0c8f9e1c9958702a28d59b4', 'c43dd619f429ed5059ca9d085f67b53dc32e536dcf98090a7ec0690aa364e7bd'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', '978946a2ddbfa27515d5a17d7b395cc21976fae854e57c98f612675a77a48892'], ['c43dd619f429ed5059ca9d085f67b53dc32e536dcf98090a7ec0690aa364e7bd', '978946a2ddbfa27515d5a17d7b395cc21976fae854e57c98f612675a77a48892'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', '4d3c7493b6e8b2acd9a7080ff21ee9797f4100fcc66c41ef813f8d471f141c11'], ['978946a2ddbfa27515d5a17d7b395cc21976fae854e57c98f612675a77a48892', '4d3c7493b6e8b2acd9a7080ff21ee9797f4100fcc66c41ef813f8d471f141c11'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', '23b6d4b3f718bad1376f5b2fcd56598ec08d2fd545d19367c979088442f8f529'], ['4d3c7493b6e8b2acd9a7080ff21ee9797f4100fcc66c41ef813f8d471f141c11', '23b6d4b3f718bad1376f5b2fcd56598ec08d2fd545d19367c979088442f8f529'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', '218869f71ff5f53f252d24191b002adc66fa7cc105f32c7f73ceb59f531086ce'], ['23b6d4b3f718bad1376f5b2fcd56598ec08d2fd545d19367c979088442f8f529', '218869f71ff5f53f252d24191b002adc66fa7cc105f32c7f73ceb59f531086ce'], ['218869f71ff5f53f252d24191b002adc66fa7cc105f32c7f73ceb59f531086ce', '918c8be65ece1933bd516fdfcca32a576fa93ca9efe809bfbc203d367b4901c6'], ['23b6d4b3f718bad1376f5b2fcd56598ec08d2fd545d19367c979088442f8f529', '918c8be65ece1933bd516fdfcca32a576fa93ca9efe809bfbc203d367b4901c6'], ['9a0ef1a5dc3cef55a7f1339d3c5682a95457516c5cd86f3debfdae0f33b01a19', '918c8be65ece1933bd516fdfcca32a576fa93ca9efe809bfbc203d367b4901c6']], 'window_runtime.py': [['73561f32797b95251516e27b930f0082b9ea294517ef60473747126ffbe2c8ac', 'b5638fd25d20a84afb118b6f465f1f9cc9b0e92d6d52f91fc41af4b4b1f7f5c7'], ['01991b4b3604dbff4d629066c92d616ea050fd48a5911a48ecff04ed53df39bb', '1f500e0a4321775b41ab98d201b5ac22ed53d3d96de8f7d109f465450cb4e254'], ['01991b4b3604dbff4d629066c92d616ea050fd48a5911a48ecff04ed53df39bb', 'c554d438339f556944bd49e73fd0b0d03e59337b95dd90407b609d4e96a8dff9'], ['1f500e0a4321775b41ab98d201b5ac22ed53d3d96de8f7d109f465450cb4e254', 'c554d438339f556944bd49e73fd0b0d03e59337b95dd90407b609d4e96a8dff9']], 'artifact_store.py': [['4dccbe54ab4877f699d016022662c2a0be6c6f688b7dc1d9a5a6720da7227deb', '6bf09afbf259ded426d653809428bb9a31cdf70fa794b29479a9b59bf6c884b0'], ['4dccbe54ab4877f699d016022662c2a0be6c6f688b7dc1d9a5a6720da7227deb', 'ef8894a3a800314f5ce0dfd1a773b198b358684042436eba68ad7e9f37652636'], ['6bf09afbf259ded426d653809428bb9a31cdf70fa794b29479a9b59bf6c884b0', 'ef8894a3a800314f5ce0dfd1a773b198b358684042436eba68ad7e9f37652636']], 'normal_observed_projection.py': ['9d0540ccfecd1c4a311a6771c1bf4e589e9dbb3ef995a917f087112ff84cbaa3', '87dfbc27d6295dc53daec10797e40c5529a3c12d75c476b54e579bebcca2b8c6'], 'oer_source_registry.py': ['6b23b2d6451f839a80f359edfaa0428b31a461eb6e45c61f689a83d4db8135f1', 'a38a69c3c75ee153b8c7326ebd8c7994e16fe3d3cad79b4fb3822d2ed8f7e406'], 'oer_unified_server.py': [['23e45cb6b58296c6d3b9c3cd77e56f9dbbe6a9518cfd416318f4a842e4649a02', '7874878dcc8620b0d3aab788d13f1e45d6d344ff442ec4504e94fe41d212edcc'], ['23e45cb6b58296c6d3b9c3cd77e56f9dbbe6a9518cfd416318f4a842e4649a02', '7874878dcc8620b0d3aab788d13f1e45d6d344ff442ec4504e94fe41d212edcc']]}

# These exact transport changes preserve canonical chunks and embedding inputs.
TRANSPORT_SOURCE_HASHES = {'window_runtime.py': [('fd1912557d8befe33f8340b13425d6125da26afd7cc3a2801d86c6859f2f5d6b', '6556bd0827da2a97908c5b58975f7e5ce55ce199025ce441835f26254e280044'), ('fd1912557d8befe33f8340b13425d6125da26afd7cc3a2801d86c6859f2f5d6b', '21d248dffb32f4832ef31749472060ee4279d378b271afce82cfa338f7d8082b'), ('6556bd0827da2a97908c5b58975f7e5ce55ce199025ce441835f26254e280044', '21d248dffb32f4832ef31749472060ee4279d378b271afce82cfa338f7d8082b')], 'index_integrity.py': ('73a5ccd44a7aca88042a936188e4c8ba039ab32bdc5912ab01cfb4258f9609a2', '6437ae55d77197247e94161937c3f5c62c9794d0cf6c256dfd5377aa0f443bcc'), 'oer_request_evidence.py': [('b0b34f5b26143271561f9a1b2b05d740e52e58a796363689994ab64982ab40fc', '94dc8042fd380a75fb4e19465116343d5d13e6e3760cf8c64ee3de6de9da7692'), ('94dc8042fd380a75fb4e19465116343d5d13e6e3760cf8c64ee3de6de9da7692', 'ab3a67f367cec82e4e899fac892963ebc87f445ffc3d823a15cbc865d8d2fd16')], 'oer_native_collection_server.py': ('9664a78f895d80e071c8f6be3de761dc19f17ca60f8094ecfad94ad38da92464', '746a3a5eab6ba1c7233bf3cab74ec1b138fc429913e0f04a05e07b7c697f20b0'), 'unified_source_registry.py': [('2a529e35bbcdee678d0df46b15d27b410c506ba1c3c92daf615a3b651268036f', '82435a6b442a2d8c27cf2b50c5a6cf816b196b43052bec52f149908825695bd7'), ('82435a6b442a2d8c27cf2b50c5a6cf816b196b43052bec52f149908825695bd7', '8d37aee0304ca2ca645abfbc964e3c5233af2a9b95e8dc9c7deb8fb108b7c848'), ('82435a6b442a2d8c27cf2b50c5a6cf816b196b43052bec52f149908825695bd7', 'c2cb95c9465d6ee6f55afbc84ab58f5c1dc46c5d2ee0aa997588c6801e9af7ad'), ('8d37aee0304ca2ca645abfbc964e3c5233af2a9b95e8dc9c7deb8fb108b7c848', 'c2cb95c9465d6ee6f55afbc84ab58f5c1dc46c5d2ee0aa997588c6801e9af7ad')], 'retrieval_source_units.py': [('428e0a2025efd7076d453d62869b995009895f526890a3623868bee837fb3b0c', '06e62d9d4bac75c7608f9b912541f4ad1947b83ce87035e8ee78ce7b92f10273'), ('428e0a2025efd7076d453d62869b995009895f526890a3623868bee837fb3b0c', 'e5c0de7ccff414c467c2007b698030b147f67ff87563a5b4c6396419af1910f2'), ('06e62d9d4bac75c7608f9b912541f4ad1947b83ce87035e8ee78ce7b92f10273', 'e5c0de7ccff414c467c2007b698030b147f67ff87563a5b4c6396419af1910f2')], 'source_read_server.py': [('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', 'f94913ed00972b82765fcd40077aafabfba5c85ce263775ddb57607554cd608a'), ('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', '0de7739fbfa4fa44c1014c1069bb176406f6eb57f680bebdf0f79b7767d1741c'), ('f94913ed00972b82765fcd40077aafabfba5c85ce263775ddb57607554cd608a', '0de7739fbfa4fa44c1014c1069bb176406f6eb57f680bebdf0f79b7767d1741c'), ('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', '7426eb745af88b24977140d8ad6920bf256948175e0bbb693311b5c9e6fffd71'), ('0de7739fbfa4fa44c1014c1069bb176406f6eb57f680bebdf0f79b7767d1741c', '7426eb745af88b24977140d8ad6920bf256948175e0bbb693311b5c9e6fffd71'), ('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', 'be9f359d12536ef04da7c92603b289ad7407db22ac60552e07527f986e5cd1a3'), ('7426eb745af88b24977140d8ad6920bf256948175e0bbb693311b5c9e6fffd71', 'be9f359d12536ef04da7c92603b289ad7407db22ac60552e07527f986e5cd1a3'), ('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', 'f4f47abff22dfdb596ca6a747c93f4f21847ddae615e119047f9bfd7a4410da0'), ('be9f359d12536ef04da7c92603b289ad7407db22ac60552e07527f986e5cd1a3', 'f4f47abff22dfdb596ca6a747c93f4f21847ddae615e119047f9bfd7a4410da0'), ('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', 'dc8dc1783c2e6e6a438f9e4b92b08fb0cd383376b9d4bf562708eb52213b8ea7'), ('f4f47abff22dfdb596ca6a747c93f4f21847ddae615e119047f9bfd7a4410da0', 'dc8dc1783c2e6e6a438f9e4b92b08fb0cd383376b9d4bf562708eb52213b8ea7'), ('dc8dc1783c2e6e6a438f9e4b92b08fb0cd383376b9d4bf562708eb52213b8ea7', '2bef1ed18e1abac73bd45408c28df435307eafb72a06d2ef52551b85813f634c'), ('f4f47abff22dfdb596ca6a747c93f4f21847ddae615e119047f9bfd7a4410da0', '2bef1ed18e1abac73bd45408c28df435307eafb72a06d2ef52551b85813f634c'), ('fbbc982032bfd0c995cd49085273285d575a7ce8ba270e6403b95d88748d05ae', '2bef1ed18e1abac73bd45408c28df435307eafb72a06d2ef52551b85813f634c')], 'artifact_store.py': [('190189e2f9ab6234adf90d5ef2c22c485838fc44938d7aa08684f3fe016ed577', '1a0b7adb3c6091764c23b713da4904f2cb3bb993e97993de736105a74ff758be'), ('190189e2f9ab6234adf90d5ef2c22c485838fc44938d7aa08684f3fe016ed577', 'ef2025871f99c588e55bc52fc0634fc08333e4c2dd824674d6214bfff4f2db69'), ('1a0b7adb3c6091764c23b713da4904f2cb3bb993e97993de736105a74ff758be', 'ef2025871f99c588e55bc52fc0634fc08333e4c2dd824674d6214bfff4f2db69')], 'server.py': [('3a19f496eb6e34cf6b6b86b0809fbb67cea9833521741a0ef5d646362070a218', '5ed19e470cc91fee75f301fe2e3088c8b4d932572d8cedd9f7208966365252d3')], 'oer_server.py': [('002d0d0d7acaaa510c976d4560f50d80455332d472c1a1b28590710806655dea', 'b9a148008376e814c926d48b53b9b5ec2e941b13dbdf15a7ba14dc59f379be9b'), ('875bed7c808c10aad98cc483c0fcd81633a1e68b6d2689e8788dcd3d4815b277', '597adf2d71e9c8d18dea3b1ef3a882f6b4eba599e87c9f94dd9383107d396e8a'), ('875bed7c808c10aad98cc483c0fcd81633a1e68b6d2689e8788dcd3d4815b277', '9fbe8b41368ae6fd53754ceec180f10c2c4f319c6bbab6d9a05aa96e0757094e'), ('597adf2d71e9c8d18dea3b1ef3a882f6b4eba599e87c9f94dd9383107d396e8a', '9fbe8b41368ae6fd53754ceec180f10c2c4f319c6bbab6d9a05aa96e0757094e'), ('002d0d0d7acaaa510c976d4560f50d80455332d472c1a1b28590710806655dea', '9fbe8b41368ae6fd53754ceec180f10c2c4f319c6bbab6d9a05aa96e0757094e'), ('b9a148008376e814c926d48b53b9b5ec2e941b13dbdf15a7ba14dc59f379be9b', '9fbe8b41368ae6fd53754ceec180f10c2c4f319c6bbab6d9a05aa96e0757094e')], 'oer_native_pdf_server.py': [('91c7e3f893bb598d6ee617b8b3d0fd8977ffc26ecb974fc9b89645e896943b30', '535b8c3037ca0620a4889e57da7b13f4f80c83c402744b91bdc3b6503d1914ce'), ('91c7e3f893bb598d6ee617b8b3d0fd8977ffc26ecb974fc9b89645e896943b30', '535b8c3037ca0620a4889e57da7b13f4f80c83c402744b91bdc3b6503d1914ce')], 'normal_observed_projection.py': ('3043484ecc05c9e0e38ef63d4e717a90ed204ea63583881004333f572b788815', '94d1ca43f5d3f873e57b9a75fb34a3b29dda265e1d8e5416dc05dd513b6847e3'), 'oer_source_registry.py': ('12397853b2c7cea36d8e5df28e82351a5fb49c834db418153d477dec9e20dc36', '18bb9167479bbe0aafaa60bc095115e78bbaac2d0ca6c91a015635eaa66d1e00'), 'oer_unified_server.py': [('dd81fcc7a3c16e14ca528512abd46c00e897eb2f7576fa59335d026258dc0061', '79f998aaebefb53df36fa477ef171b797a786bfcb41dc5c2a0606847a990c10e'), ('bd4a8aa8be9b324336c5454ca004f84f5dc9b8c457154625223ce95b868e3ec1', '79f998aaebefb53df36fa477ef171b797a786bfcb41dc5c2a0606847a990c10e')]}
PREVIOUS_PORTABLE_AST_PAIR = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '74ea7d680049dd6829a95b999ae9e3ca5ddbdbc24071ac12c03165478974bda5']
PREVIOUS_PORTABLE_AST_PAIR_DUAL12 = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '6c098b6bd81edc402bf6c7ee8d2b8c4031ff1b1577e65de8cb273f2f3a1b621e']
PREVIOUS_PORTABLE_AST_PAIR_DUAL13 = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '1869e9bc9e1872d232cffce05434d680a2f4b44d79f4daf03409b6294c015021']
PORTABLE_DUAL13_SHA = 'b1ef47cab8960223985d314c22cf565f6bd00235a3b42cca9a9e06b7655edc5d'
PREVIOUS_PORTABLE_AST_PAIR_COMBINED01 = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', 'a3de69880d2e8ddd4a7c81c22d3814dd173da8024d7f0edbf8da0c3f66a7f76b']
PORTABLE_COMBINED01_SHA = '792c51e5d325f433f3a198a66cb3e69efbad69b68cc611ec75eaf66a38b30ea4'
PORTABLE_PREDECESSOR_SHA = '936032bb713c892778bedd167571a0f5fedf9c60471e12927af5a032d6a78d4b'

PREVIOUS_PORTABLE_AST_PAIR_STAGE32 = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '05b96ac418346eacd6b6f7d2f6477c1878e1f08d84c25d380a7dfffb3a378928']
PORTABLE_STAGE32_SHA = 'ed42ea440cfa8bf73035a502c0a13ed86c2888158bb31b8301fd438d4f2f847a'
PREVIOUS_PORTABLE_AST_PAIR_COMBINED03 = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '8e6dde3a1dd2cbb0fe7abf269c40ba8687c2ae3e3eafe7d3d0a61aa5206121fc']
PORTABLE_COMBINED03_SHA = '702d2fdb96ec10234cca4193dd231c0eb277eb0eb28fb046ae1131f9c5c69e78'
PREVIOUS_PORTABLE_AST_PAIR_COLD_PREPARE = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '911cff7fcb00dbd69c28ca5b0241a74acf51c7c58e777a0c5db913175a66115b']
PORTABLE_COLD_PREPARE_SHA = '7443c08cc291ca04b3698c8e4562a9545ae97207227709fd33912827a598bff3'

PREVIOUS_PORTABLE_AST_PAIR_SINGLE_MANIFEST = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '0603cb08bd157545e61d22bcb907a06253e96201031ebca75420c1980d7a559e']
PORTABLE_SINGLE_MANIFEST_PREDECESSOR_SHA = '752877ba6e76a7909f384372d109348d273e1d58d4de8529ba419937954eb447'

PREVIOUS_PORTABLE_AST_PAIR_SINGLE_PARSE = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '5f15335c6d3a0147151f5d96b27f355b0027062265475b12adf18dc9685536d1']
PORTABLE_SINGLE_PARSE_SHA = 'baf9110da4fc1baae60e2d3fea8bdde6a1b167313c89b07911ba775f86ff893c'

PREVIOUS_PORTABLE_AST_PAIR_COMPACT_NORMAL = ['1406e78076f42c192359d47be0b061ddc01f5190bbb8e9dc466cb72ca5e8128c', '350b2cb21a20c70ad750c8bb845c6b7ad55a40bc15642d1aff08ecd71cb7d3f0']
PORTABLE_COMPACT_NORMAL_SHA = '68eba91b5720f3984ce71cf947e2e9e98e17b10fc18f40fd733ecd01b4db6c18'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def portable(identity):
    return {k: v for k, v in identity.items() if k != 'resolvedSnapshot'}


def migration_signature_version(stored, current):
    return stored == current and stored in (3, 4, 5, 6) or (stored, current) in ((3, 5), (4, 6))


def comparable(value):
    result = copy.deepcopy(value)
    result.pop('algorithms', None)
    result['signatureVersion'] = {3: 5, 4: 6}.get(result.get('signatureVersion'), result.get('signatureVersion'))
    result['tokenizerAndModelFiles'] = portable(result.get('tokenizerAndModelFiles', {}))
    return result


def legacy_candidate(stored, current):
    return stored.get('signatureVersion') in (3, 4) and comparable(stored) == comparable(current)


def transport_candidate(stored, current):
    if stored.get('signatureVersion') != 6 or current.get('signatureVersion') != 6:
        return False
    if {k: v for k, v in stored.items() if k != 'algorithms'} != {k: v for k, v in current.items() if k != 'algorithms'}:
        return False
    before, after = stored.get('algorithms'), current.get('algorithms')
    if not isinstance(before, dict) or not before or not isinstance(after, dict) or set(before) != set(after):
        return False
    changed = {name for name in before if before[name] != after[name]}
    return bool(changed) and all(
        before[name] in (PORTABLE_PREDECESSOR_SHA, PORTABLE_DUAL13_SHA, PORTABLE_COMBINED01_SHA, PORTABLE_STAGE32_SHA, PORTABLE_COMBINED03_SHA, PORTABLE_COLD_PREPARE_SHA, PORTABLE_SINGLE_MANIFEST_PREDECESSOR_SHA, PORTABLE_SINGLE_PARSE_SHA, PORTABLE_COMPACT_NORMAL_SHA) if name == 'portable_index_signature.py'
        else (TRANSPORT_SOURCE_HASHES.get(name) == (before[name], after[name])
              or (before[name], after[name]) in TRANSPORT_SOURCE_HASHES.get(name, ()))
        for name in changed)


def computation_proof(name, before, after):
    def fingerprint(source):
        module = ast.parse(source)
        if name == 'portable_index_signature.py':
            # Normalize only the self-digest literal; the complete executable AST still binds.
            for node in module.body:
                if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id == 'APPROVED_SOURCE_ADAPTERS' for target in node.targets):
                    for key, value in zip(node.value.keys, node.value.values):
                        if isinstance(key, ast.Constant) and key.value == name:
                            value.elts[1] = ast.Constant(value='<self>')
        return sha(ast.dump(module, include_attributes=False).encode('utf-8'))
    pair = [fingerprint(before), fingerprint(after)]
    fail(pair[0] == pair[1] or (APPROVED_SOURCE_ADAPTERS.get(name) == pair or pair in APPROVED_SOURCE_ADAPTERS.get(name, []) or
         (name == 'portable_index_signature.py' and (pair in (PREVIOUS_PORTABLE_AST_PAIR, PREVIOUS_PORTABLE_AST_PAIR_DUAL12, PREVIOUS_PORTABLE_AST_PAIR_DUAL13, PREVIOUS_PORTABLE_AST_PAIR_COMBINED01, PREVIOUS_PORTABLE_AST_PAIR_STAGE32, PREVIOUS_PORTABLE_AST_PAIR_COMBINED03, PREVIOUS_PORTABLE_AST_PAIR_COLD_PREPARE, PREVIOUS_PORTABLE_AST_PAIR_SINGLE_MANIFEST, PREVIOUS_PORTABLE_AST_PAIR_SINGLE_PARSE, PREVIOUS_PORTABLE_AST_PAIR_COMPACT_NORMAL)
          or pair in ([PREVIOUS_PORTABLE_AST_PAIR_DUAL13[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_COMBINED01[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_STAGE32[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_COMBINED03[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_COLD_PREPARE[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_SINGLE_MANIFEST[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_SINGLE_PARSE[1], APPROVED_SOURCE_ADAPTERS[name][1]], [PREVIOUS_PORTABLE_AST_PAIR_COMPACT_NORMAL[1], APPROVED_SOURCE_ADAPTERS[name][1]])))),
         'INDEX_ADAPTER_COMPUTATION_CHANGED',
         'Exact reviewed predecessor and source adapter required; unreviewed computation or module changes are rejected',
         source=name)


def build(predecessor, current):
    predecessor, current = Path(predecessor), Path(current)
    old = {p.name: p.read_bytes() for p in predecessor.glob('*.py') if not p.name.startswith('test_')}
    new = {p.name: p.read_bytes() for p in current.glob('*.py') if not p.name.startswith('test_')}
    changed = {name: raw.decode('utf-8') for name, raw in old.items()
               if name in new and sha(raw) != sha(new[name])}
    for name, source in changed.items():
        computation_proof(name, source, new[name].decode('utf-8'))
    return {'protocol': PROTOCOL, 'predecessorHashes': {k: sha(v) for k, v in old.items()},
            'currentHashes': {k: sha(v) for k, v in new.items()}, 'predecessorSources': changed,
            'scope': 'explicit audited source adapter only; model bytes, recipe, corpus and actual vector seals remain mandatory'}


def matches(stored, current, source_root, environ=None):
    if stored == current:
        return True
    if not legacy_candidate(stored, current) and not transport_candidate(stored, current):
        return False
    env = os.environ if environ is None else environ
    path, expected = env.get(ENV_PATH), env.get(ENV_SHA)
    if not path or not expected:
        return False
    with Path(path).open('rb') as handle:
        raw = handle.read(MAX_BYTES + 1)
    fail(len(raw) <= MAX_BYTES, 'INDEX_ADAPTER_TOO_LARGE', 'Signature adapter exceeds its finite byte bound')
    fail(sha(raw) == expected, 'INDEX_ADAPTER_SHA_CHANGED', 'External signature-adapter SHA differs')
    receipt = json.loads(raw, object_pairs_hook=unique_object)
    fail(receipt.get('protocol') == PROTOCOL, 'INDEX_ADAPTER_PROTOCOL', 'Known signature adapter required')
    before, after = receipt['predecessorHashes'], receipt['currentHashes']
    fail(isinstance(stored.get('algorithms'), dict) and bool(stored['algorithms'])
         and isinstance(current.get('algorithms'), dict) and bool(current['algorithms']),
         'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'Complete predecessor and current algorithm identities required')
    for name, digest in stored['algorithms'].items():
        if name == 'nativePdfFrameReaderIdentity':
            fail(current['algorithms'].get(name) == digest, 'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'PDF frame-reader identity must remain exact')
            continue
        fail(before.get(name) == digest, 'INDEX_ADAPTER_PREDECESSOR_CHANGED', 'Stored algorithm differs from the frozen predecessor', source=name)
        fail(after.get(name) == digest or name in receipt['predecessorSources'],
             'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'Changed indexed dependency requires its reviewed adapter', source=name)
    for name, digest in current['algorithms'].items():
        if name == 'nativePdfFrameReaderIdentity':
            fail(stored['algorithms'].get(name) == digest, 'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'PDF frame-reader identity must remain exact')
            continue
        fail(name in stored['algorithms'] or name == 'portable_index_signature.py',
             'INDEX_ADAPTER_DEPENDENCY_CHANGED', 'New indexed dependency has no predecessor adapter', source=name)
        fail(after.get(name) == digest and Path(name).name == name and name.endswith('.py')
             and sha((Path(source_root) / name).read_bytes()) == digest,
             'INDEX_ADAPTER_CURRENT_CHANGED', 'Current source differs from the pinned adapter', source=name)
    for name, source in receipt['predecessorSources'].items():
        fail(Path(name).name == name and sha(source.encode('utf-8')) == before.get(name),
             'INDEX_ADAPTER_PREDECESSOR_CHANGED', 'Embedded predecessor bytes differ')
        new = (Path(source_root) / name).read_bytes()
        fail(sha(new) == after.get(name), 'INDEX_ADAPTER_CURRENT_CHANGED', 'Adapter source changed', source=name)
        computation_proof(name, source, new.decode('utf-8'))
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--predecessor', required=True)
    parser.add_argument('--current', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args(); output = Path(args.output)
    raw = json.dumps(build(args.predecessor, args.current), ensure_ascii=False, sort_keys=True).encode('utf-8')
    fail(len(raw) <= MAX_BYTES, 'INDEX_ADAPTER_TOO_LARGE', 'Signature adapter exceeds its finite byte bound')
    with output.open('xb') as handle:
        handle.write(raw)
    print(json.dumps({'path': str(output.resolve()), 'sha256': sha(raw), 'bytes': len(raw), 'protocol': PROTOCOL}))


if __name__ == '__main__':
    main()
