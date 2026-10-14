package com.opsflow.cmdb;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.opsflow.audit.AuditLogService;
import com.opsflow.auth.AuthService;
import com.opsflow.ticket.Ticket;
import com.opsflow.ticket.TicketMapper;
import com.opsflow.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CmdbAssetServiceTest {
    @Mock
    private CmdbAssetMapper assetMapper;
    @Mock
    private CmdbAssetRelationMapper relationMapper;
    @Mock
    private TicketAssetLinkMapper ticketAssetLinkMapper;
    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketService ticketService;
    @Mock
    private AuthService authService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private CmdbAssetService service;

    @Test
    void createsTenantScopedAssetAndRecordsAudit() {
        when(authService.isStaffMember(9L, 3L)).thenReturn(true);
        when(assetMapper.insert(any(CmdbAsset.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmdbAsset.class).setId(12L);
            return 1;
        });

        CmdbAsset result = service.create(new CmdbAssetService.AssetCommand(
                "APP-001", "订单服务", AssetType.APPLICATION, AssetEnvironment.PRODUCTION,
                AssetStatus.ACTIVE, AssetCriticality.HIGH, 9L, "交易平台", "生产集群 A",
                "订单核心 API", "owner=platform"), 3L, 8L);

        assertEquals(12L, result.getId());
        assertEquals(3L, result.getTenantId());
        assertEquals("APP-001", result.getAssetNo());
        verify(auditLogService).record(eq(3L), eq(8L), eq("CMDB_ASSET"), eq(12L),
                eq("CMDB_ASSET_CREATED"), eq(null), eq(result), eq("创建配置项资产"));
    }

    @Test
    void rejectsRelationToItselfBeforeWriting() {
        CmdbAsset source = asset(5L, "APP-001");
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(source);

        assertThrows(RuntimeException.class, () -> service.createRelation(5L,
                new CmdbAssetService.RelationCommand(5L, AssetRelationType.DEPENDS_ON, null), 3L, 8L));
        verify(relationMapper, never()).insert(any(CmdbAssetRelation.class));
    }

    @Test
    void linksAssetToTicketIdempotently() {
        Ticket ticket = new Ticket();
        ticket.setId(41L);
        when(ticketService.get(41L, 3L)).thenReturn(ticket);
        CmdbAsset asset = asset(12L, "APP-001");
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset);
        when(ticketAssetLinkMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(ticketAssetLinkMapper.insert(any(TicketAssetLink.class))).thenAnswer(invocation -> 1);

        CmdbAssetService.TicketAssetView result = service.linkTicketAsset(41L,
                new CmdbAssetService.TicketAssetCommand(12L, "AFFECTED", "用户无法下单"), 3L, 8L);

        assertEquals(41L, result.ticketId());
        assertEquals("AFFECTED", result.linkType());
        assertEquals("APP-001", result.asset().getAssetNo());
        verify(ticketAssetLinkMapper).insert(any(TicketAssetLink.class));
    }

    @Test
    void rejectsInvalidTicketLinkType() {
        Ticket ticket = new Ticket();
        ticket.setId(41L);
        when(ticketService.get(41L, 3L)).thenReturn(ticket);
        when(assetMapper.selectOne(any(Wrapper.class))).thenReturn(asset(12L, "APP-001"));

        assertThrows(RuntimeException.class, () -> service.linkTicketAsset(41L,
                new CmdbAssetService.TicketAssetCommand(12L, "UNKNOWN", null), 3L, 8L));
        verify(ticketAssetLinkMapper, never()).insert(any(TicketAssetLink.class));
    }

    private CmdbAsset asset(Long id, String assetNo) {
        CmdbAsset asset = new CmdbAsset();
        asset.setId(id);
        asset.setTenantId(3L);
        asset.setAssetNo(assetNo);
        asset.setName("订单服务");
        asset.setAssetType(AssetType.APPLICATION);
        asset.setEnvironment(AssetEnvironment.PRODUCTION);
        asset.setStatus(AssetStatus.ACTIVE);
        asset.setCriticality(AssetCriticality.HIGH);
        return asset;
    }
}
